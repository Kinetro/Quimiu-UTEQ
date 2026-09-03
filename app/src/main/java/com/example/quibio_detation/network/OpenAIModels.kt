package com.example.quibio_detation.network

import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// Request para POST /v1/responses
// ---------------------------------------------------------------------------

data class ResponsesRequest(
    val model: String,
    val input: String,
    val tools: List<Tool>? = null,
    // Encadena esta pregunta a una respuesta anterior de OpenAI para mantener contexto
    // de conversación (chat multi-turno) sin tener que reenviar todo el historial.
    @SerializedName("previous_response_id")
    val previousResponseId: String? = null
)

data class Tool(
    val type: String = "file_search",
    // TODO: reemplazar por el/los vector_store_id reales (ver BuildConfig.OPENAI_VECTOR_STORE_ID,
    // definido en local.properties) con la documentación de los equipos del laboratorio.
    @SerializedName("vector_store_ids")
    val vectorStoreIds: List<String>
)

// ---------------------------------------------------------------------------
// Response de POST /v1/responses (estructura simplificada: solo se mapean los
// campos que la app necesita para mostrar el texto final de la respuesta).
// ---------------------------------------------------------------------------

data class ResponsesApiResponse(
    val id: String?,
    val output: List<OutputItem>?
) {
    /** Concatena todo el texto de salida del asistente en un solo String legible. */
    fun extractText(): String {
        val text = output
            ?.filter { it.type == "message" }
            ?.flatMap { it.content.orEmpty() }
            ?.filter { it.type == "output_text" }
            ?.mapNotNull { it.text }
            ?.joinToString("\n")

        return text?.ifBlank { null } ?: "No se recibió respuesta del asistente."
    }
}

data class OutputItem(
    val type: String?,
    val content: List<ContentItem>?
)

data class ContentItem(
    val type: String?,
    val text: String?
)
