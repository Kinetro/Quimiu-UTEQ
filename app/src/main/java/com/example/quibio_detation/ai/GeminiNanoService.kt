package com.example.quibio_detation.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

/**
 * Envoltorio sobre com.google.mlkit:genai-prompt (ML Kit GenAI "Prompt API"), la API
 * pública que ejecuta Gemini Nano 100% on-device (sin internet, sin API key, sin costo).
 *
 * Requisitos de dispositivo (los valida ML Kit internamente en [checkAvailability]):
 *  - Android 14+ (API 34) y hardware con AICore (Pixel 8/9/10, Samsung Galaxy S24+/S25+, etc.).
 *    En dispositivos más viejos o sin AICore, checkStatus() devuelve UNAVAILABLE.
 *  - Bootloader bloqueado (de fábrica); en dispositivos rooteados/desbloqueados la API
 *    también reporta UNAVAILABLE.
 *  - minSdk de la librería en sí es 26, pero por debajo de Android 14 siempre será UNAVAILABLE.
 *
 * NOTA (API en beta): com.google.mlkit:genai-prompt está en beta ("sin SLA ni política de
 * deprecación" según la doc oficial), así que los nombres de clases/propiedades usados acá
 * (verificados contra la versión 1.0.0-beta2) podrían cambiar en versiones futuras. Si al
 * subir la versión de la librería deja de compilar, revisar contra:
 * https://developers.google.com/ml-kit/genai/prompt/android/get-started
 */
sealed class ModelAvailability {
    /** Gemini Nano ya está descargado en el dispositivo y listo para generar respuestas. */
    data object Available : ModelAvailability()

    /** El dispositivo es compatible pero el modelo todavía no se descargó (primer uso). */
    data object NeedsDownload : ModelAvailability()

    /** Hay una descarga en curso (por ejemplo, iniciada por otra pantalla/instancia). */
    data object Downloading : ModelAvailability()

    /** Dispositivo no compatible (Android < 14, sin AICore, bootloader desbloqueado, etc.). */
    data object Unsupported : ModelAvailability()
}

class GeminiNanoService {

    // Generation.getClient() crea el cliente con la configuración de generación por defecto.
    // Se puede pasar un GenerationConfig (temperature, topK, maxOutputTokens, etc.) con
    // Generation.getClient(options) si se necesita ajustar el estilo de las respuestas.
    private val generativeModel by lazy { Generation.getClient() }

    /** Consulta el estado actual del feature sin iniciar ninguna descarga. */
    suspend fun checkAvailability(): ModelAvailability = try {
        when (generativeModel.checkStatus()) {
            FeatureStatus.AVAILABLE -> ModelAvailability.Available
            FeatureStatus.DOWNLOADABLE -> ModelAvailability.NeedsDownload
            FeatureStatus.DOWNLOADING -> ModelAvailability.Downloading
            else -> ModelAvailability.Unsupported
        }
    } catch (e: Exception) {
        // Cualquier error inesperado de ML Kit (servicio de Play no disponible, etc.) se
        // trata como "no soportado" para que el caller haga fallback sin romper la app.
        ModelAvailability.Unsupported
    }

    /**
     * Dispara la descarga de los pesos de Gemini Nano (varios cientos de MB, una sola vez
     * por dispositivo) y reporta el progreso en [onProgress] (0-100). Requiere conexión a
     * internet SOLO esta vez; una vez descargado, [generateAnswer] funciona sin red.
     */
    suspend fun downloadModel(onProgress: (percent: Int) -> Unit): Result<Unit> {
        var lastKnownTotalBytes = 1L
        var failure: Throwable? = null
        try {
            generativeModel.download()
                .catch { e -> failure = e }
                .collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            lastKnownTotalBytes = status.bytesToDownload.coerceAtLeast(1L)
                            onProgress(0)
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val pct = (status.totalBytesDownloaded * 100 / lastKnownTotalBytes)
                                .toInt().coerceIn(0, 100)
                            onProgress(pct)
                        }
                        is DownloadStatus.DownloadCompleted -> onProgress(100)
                        is DownloadStatus.DownloadFailed -> failure = status.e
                    }
                }
        } catch (e: Exception) {
            failure = e
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * Genera una respuesta a [question] usando ÚNICAMENTE el contenido de [documentContext]
     * como fuente de verdad. [documentContext] ya viene recortado a los fragmentos más
     * relevantes del documento del equipo (ver EquipmentQaRepository / KeywordSearch.
     * retrieveFragments) — el documento completo nunca llega hasta acá.
     */
    suspend fun generateAnswer(documentContext: String, question: String): Result<String> {
        return try {
            val response = generativeModel.generateContent(buildPrompt(documentContext, question))
            val text = response.candidates.firstOrNull()?.text?.trim()
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("Gemini Nano no devolvió texto"))
            } else {
                Result.success(text)
            }
        } catch (e: GenAiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildPrompt(documentContext: String, question: String): String {
        // Gemini Nano en el dispositivo soporta un contexto acotado (~4000 tokens de entrada,
        // unas 3000 palabras en inglés; menos en español por tokenización). La recuperación de
        // fragmentos relevantes (en vez del documento completo) se hace en EquipmentQaRepository
        // antes de llegar acá, ver KeywordSearch.retrieveFragments.
        return """
            Eres un asistente técnico de laboratorio. Responde la pregunta del usuario
            basándote ÚNICAMENTE en la información del siguiente documento del equipo.
            Si la respuesta no está en el documento, responde exactamente:
            "No encuentro esa información en el documento del equipo."
            No inventes datos que no estén en el texto.

            --- DOCUMENTO DEL EQUIPO ---
            $documentContext
            --- FIN DEL DOCUMENTO ---

            Pregunta del usuario: $question
            Respuesta:
        """.trimIndent()
    }

    /** Libera los recursos del motor de inferencia. Llamar cuando la Activity/ViewModel se destruye. */
    fun close() {
        generativeModel.close()
    }
}
