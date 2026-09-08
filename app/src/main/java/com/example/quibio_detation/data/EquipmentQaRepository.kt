package com.example.quibio_detation.data

import com.example.quibio_detation.ai.GeminiCloudService
import com.example.quibio_detation.ai.GeminiNanoService
import com.example.quibio_detation.ai.ModelAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Respuesta de un turno de chat sobre el equipo seleccionado. */
data class ChatReply(val text: String)

/**
 * Reemplaza al antiguo OpenAIRepository (API en la nube + RAG con vector store) por un
 * flujo con Gemini Nano vía ML Kit GenAI:
 *
 *   1. Se busca el documento del equipo: primero el .txt de la ficha técnica/manual
 *      (ver EquipmentDocumentProvider, alimentado por los PDFs de "Documentos de aparatos
 *      de laboratorio/"); si no existe, se usa el texto de info_equipos.json como contexto.
 *   2. En vez de mandar ese documento completo al LLM, se recuperan solo los fragmentos
 *      más relevantes para la pregunta (ver KeywordSearch.retrieveFragments, patrón RAG por
 *      palabras clave/párrafo) y esos fragmentos son lo único que entra al prompt.
 *   3. Si Gemini Nano está disponible en el dispositivo, se le pide que responda la
 *      pregunta del usuario basándose únicamente en esos fragmentos.
 *   4. Si el dispositivo no es compatible (Android < 14, sin AICore, etc.) o la generación
 *      con Gemini Nano falla, se intenta con Gemini Cloud (Google AI SDK con API key, ver
 *      GeminiCloudService) como respaldo en la nube.
 *   5. Si Gemini Cloud también falla (sin conexión a internet, cuota agotada, etc.), se cae
 *      a una búsqueda simple de palabras clave sobre el documento completo
 *      (KeywordSearch.findAnswer), sin usar IA.
 *   6. Toda respuesta indica la fuente consultada (el .txt o la ficha local usada), y si no
 *      se encontró información se recomienda consultar al docente o responsable del laboratorio.
 *
 * Nota: a diferencia de la API de OpenAI (que encadenaba turnos con previous_response_id),
 * Gemini Nano on-device no mantiene una conversación del lado del servidor: cada pregunta
 * se responde de forma independiente contra el documento del equipo. El historial que se ve
 * en el chat es solo un registro visual en el ViewModel, no memoria real del modelo.
 */
class EquipmentQaRepository(
    private val documentProvider: EquipmentDocumentProvider,
    private val localInfoProvider: LocalEquipmentInfoProvider,
    private val geminiNano: GeminiNanoService,
    private val geminiCloud: GeminiCloudService
) {

    /**
     * @param equipmentName etiqueta del equipo tal como la reporta el detector TFLite.
     * @param question pregunta libre del usuario.
     * @param onDownloadProgress se invoca con 0-100 mientras se descarga Gemini Nano la
     *   primera vez que se usa en el dispositivo (no hace nada las siguientes veces).
     */
    suspend fun ask(
        equipmentName: String,
        question: String,
        onDownloadProgress: (percent: Int) -> Unit = {}
    ): Result<ChatReply> = withContext(Dispatchers.Default) {
        val (documentContext, source) = resolveContext(equipmentName)
            ?: return@withContext Result.success(ChatReply(NO_INFO_MESSAGE))

        val reply = when (geminiNano.checkAvailability()) {
            ModelAvailability.Available ->
                generateOrFallback(documentContext, question)

            ModelAvailability.NeedsDownload, ModelAvailability.Downloading -> {
                val downloaded = geminiNano.downloadModel(onDownloadProgress)
                if (downloaded.isSuccess) {
                    generateOrFallback(documentContext, question)
                } else {
                    cloudOrKeywordFallback(documentContext, question)
                }
            }

            ModelAvailability.Unsupported ->
                cloudOrKeywordFallback(documentContext, question)
        }

        Result.success(ChatReply(withCitation(reply, source)))
    }

    private suspend fun generateOrFallback(documentContext: String, question: String): String {
        val ragContext = ragContext(documentContext, question)

        return geminiNano.generateAnswer(ragContext, question)
            .map { addProfessorHintIfNotFound(it) }
            .getOrElse {
                // Gemini Nano falló en tiempo de ejecución (dispositivo compatible pero, por
                // ejemplo, sin cuota de AICore disponible): se intenta con Gemini Cloud antes
                // de caer al modo sin IA.
                cloudOrKeywordFallback(documentContext, question)
            }
    }

    /**
     * Fallback de dos niveles cuando Gemini Nano no está disponible o falló: primero se
     * intenta con Gemini Cloud (requiere internet + API key, ver GeminiCloudService); si
     * también falla (sin conexión, cuota agotada, API key inválida, etc.) se cae a la
     * búsqueda simple de palabras clave sobre el documento completo, sin usar IA.
     */
    private suspend fun cloudOrKeywordFallback(documentContext: String, question: String): String {
        val ragContext = ragContext(documentContext, question)

        return geminiCloud.generateAnswer(ragContext, question)
            .map { addProfessorHintIfNotFound(it) }
            .getOrElse { error ->
                // Diagnóstico temporal: se muestra el motivo real del fallo de Gemini Cloud
                // directamente en el chat (en vez de solo en logcat) porque no siempre hay
                // forma de conectar el dispositivo real por adb para revisar el log.
                val fallback = keywordFallback(documentContext, question)
                "$fallback\n\n🔧 (Gemini Cloud falló: ${error::class.simpleName}: ${error.message})"
            }
    }

    /**
     * RAG real: solo los fragmentos más relevantes para la pregunta viajan al prompt del
     * LLM, nunca el documento completo (ver KeywordSearch.retrieveFragments).
     */
    private fun ragContext(documentContext: String, question: String): String =
        KeywordSearch
            .retrieveFragments(documentContext, question, RAG_MAX_FRAGMENTS, RAG_MAX_CHARS)
            .joinToString("\n\n")
            .ifBlank { documentContext.take(RAG_MAX_CHARS) }

    private fun keywordFallback(documentContext: String, question: String): String =
        addProfessorHintIfNotFound(KeywordSearch.findAnswer(documentContext, question))

    /** Si la respuesta indica que no se encontró información, se agrega la recomendación de consultar al docente. */
    private fun addProfessorHintIfNotFound(text: String): String =
        if (NOT_FOUND_MARKERS.any { text.contains(it, ignoreCase = true) }) "$text\n\n$ASK_PROFESSOR_HINT" else text

    private fun withCitation(text: String, source: String): String = "$text\n\n📄 Fuente: $source"

    private fun resolveContext(equipmentName: String): Pair<String, String>? {
        val docText = documentProvider.getDocumentText(equipmentName)
        return when {
            docText != null -> docText to "docs_equipos/$equipmentName.txt"
            else -> localInfoProvider.getInfo(equipmentName)?.let { it to "ficha técnica local (info_equipos.json)" }
        }
    }

    companion object {
        // Presupuesto de fragmentos recuperados (RAG) que se envían al prompt de Gemini Nano,
        // muy por debajo del límite de contexto del modelo (~4000 tokens de entrada).
        private const val RAG_MAX_FRAGMENTS = 3
        private const val RAG_MAX_CHARS = 3_000

        private const val ASK_PROFESSOR_HINT =
            "Te recomendamos consultar al docente o responsable del laboratorio para más detalles."

        private val NOT_FOUND_MARKERS = listOf(
            "no encuentro esa información",
            "no encontré una coincidencia"
        )

        private const val NO_INFO_MESSAGE =
            "No se encontró información sobre este equipo en los documentos disponibles. " +
                "Te recomendamos consultar al docente o responsable del laboratorio."
    }
}
