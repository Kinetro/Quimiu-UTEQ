package com.example.quibio_detation.data

import com.example.quibio_detation.BuildConfig
import com.example.quibio_detation.network.NetworkModule
import com.example.quibio_detation.network.OpenAIApiService
import com.example.quibio_detation.network.ResponsesRequest
import com.example.quibio_detation.network.Tool
import com.example.quibio_detation.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Respuesta de un turno de chat: el texto y el id para encadenar la siguiente pregunta. */
data class ChatReply(val text: String, val responseId: String?)

/**
 * Repositorio de chat (RAG con file_search) sobre el equipo de laboratorio
 * seleccionado por el usuario. Soporta conversación multi-turno: si se pasa
 * [previousResponseId], la pregunta se encadena a esa respuesta anterior en
 * vez de perder el contexto de lo ya conversado.
 *
 * Respaldo (fallback): si falta la API key/vector store, o si la llamada HTTP falla
 * (sin conexión, timeout, 429 por falta de créditos, 500, etc.), la primera pregunta
 * (la fija de "qué es este equipo") se responde con app/src/main/assets/info_equipos.json;
 * las preguntas libres, al no poder resolverse localmente, avisan que no hay conexión.
 */
class OpenAIRepository(
    private val localInfoProvider: LocalEquipmentInfoProvider,
    private val api: OpenAIApiService = NetworkModule.openAIApi
) {

    /**
     * @param equipmentName equipo seleccionado (da contexto al modelo).
     * @param question pregunta libre del usuario; si es null, se usa la pregunta
     *   fija "qué es y para qué sirve" (primer turno rápido con el botón).
     * @param previousResponseId id de la respuesta anterior en esta conversación,
     *   o null si es el primer mensaje sobre este equipo.
     */
    suspend fun ask(
        equipmentName: String,
        question: String?,
        previousResponseId: String?
    ): Result<ChatReply> = withContext(Dispatchers.IO) {
        if (BuildConfig.OPENAI_API_KEY.isBlank() || BuildConfig.OPENAI_VECTOR_STORE_ID.isBlank()) {
            return@withContext Result.success(fallbackReply(equipmentName, question))
        }

        val prompt = when {
            question == null -> defaultPrompt(equipmentName)
            previousResponseId == null -> "Sobre el equipo de laboratorio \"$equipmentName\" del " +
                "Laboratorio de Química-Bioquímica de la UTEQ: $question"
            else -> question
        }

        val request = ResponsesRequest(
            model = Constants.OPENAI_MODEL,
            input = prompt,
            tools = listOf(Tool(vectorStoreIds = listOf(BuildConfig.OPENAI_VECTOR_STORE_ID))),
            previousResponseId = previousResponseId
        )

        try {
            val response = api.askAboutEquipment(request)
            Result.success(ChatReply(response.extractText(), response.id))
        } catch (e: Exception) {
            Result.success(fallbackReply(equipmentName, question))
        }
    }

    private fun defaultPrompt(equipmentName: String) =
        "Explícame qué es, para qué sirve y cómo se usa correctamente " +
            "el equipo de laboratorio llamado \"$equipmentName\", en el contexto del " +
            "Laboratorio de Química-Bioquímica de la UTEQ."

    private fun fallbackReply(equipmentName: String, question: String?): ChatReply {
        val text = if (question == null) {
            localInfoProvider.getInfo(equipmentName) ?: "No se encontró información local para este equipo."
        } else {
            "No tengo conexión con el asistente en este momento, así que no puedo responder preguntas libres. " +
                "Probá con el botón \"Qué es este equipo\" para ver la información básica guardada localmente."
        }
        return ChatReply(text, responseId = null)
    }
}
