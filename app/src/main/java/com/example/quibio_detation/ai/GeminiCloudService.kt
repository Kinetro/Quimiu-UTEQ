package com.example.quibio_detation.ai

import android.util.Log
import com.example.quibio_detation.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GoogleGenerativeAIException
import com.google.ai.client.generativeai.type.ServerException
import com.google.ai.client.generativeai.type.UnknownException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Fallback en la nube para EquipmentQaRepository: se usa cuando Gemini Nano on-device
 * (ver GeminiNanoService) no está disponible en el dispositivo o falla al generar una
 * respuesta. Requiere conexión a internet y una API key válida (GEMINI_API_KEY en
 * local.properties, inyectada vía BuildConfig, ver app/build.gradle.kts).
 */
class GeminiCloudService {

    private val generativeModel by lazy {
        GenerativeModel(
            // "gemini-1.5-flash" fue retirado por Google (ver ListModels de la API).
            // "gemini-flash-latest" (el modelo "grande") apunta hoy a un modelo nuevo con
            // cuota gratuita muy baja (20 solicitudes/min) que se agota enseguida; el alias
            // "-lite" apunta a un modelo más liviano con cuota gratuita mucho más generosa,
            // suficiente para esta app de preguntas/respuestas sobre un solo documento corto.
            modelName = "gemini-flash-lite-latest",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    /**
     * Genera una respuesta a [question] usando ÚNICAMENTE el contenido de [documentContext]
     * como fuente de verdad, igual que GeminiNanoService.generateAnswer.
     */
    suspend fun generateAnswer(documentContext: String, question: String): Result<String> =
        withContext(Dispatchers.IO) {
            val prompt = buildPrompt(documentContext, question)
            var lastError: Exception? = null

            // El modelo "-latest" recibe mucho tráfico y responde con errores momentáneos
            // (503 ServerException por alta demanda, o UnknownException cuando el servidor
            // devuelve algo que el SDK no logra clasificar) con cierta frecuencia; un
            // reintento corto suele bastar sin que el usuario note el fallback al buscador de
            // palabras clave innecesariamente. Errores deterministas (cuota agotada, API key
            // inválida, prompt bloqueado, etc.) NO se reintentan: reintentar no los arregla.
            repeat(1 + MAX_RETRIES) { attempt ->
                try {
                    val response = generativeModel.generateContent(prompt)
                    val text = response.text?.trim()
                    return@withContext if (text.isNullOrBlank()) {
                        Result.failure(IllegalStateException("Gemini Cloud no devolvió texto"))
                    } else {
                        Result.success(text)
                    }
                } catch (e: GoogleGenerativeAIException) {
                    val isTransient = e is ServerException || e is UnknownException
                    if (isTransient && attempt < MAX_RETRIES) {
                        lastError = e
                        delay(RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "Fallo generando respuesta con Gemini Cloud", e)
                        return@withContext Result.failure(e)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallo generando respuesta con Gemini Cloud", e)
                    return@withContext Result.failure(e)
                }
            }

            Log.w(TAG, "Gemini Cloud no respondió tras reintentos (servidor sobrecargado)", lastError)
            Result.failure(lastError ?: IllegalStateException("Gemini Cloud no respondió"))
        }

    private fun buildPrompt(documentContext: String, question: String): String {
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

    private companion object {
        private const val TAG = "GeminiCloudService"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 800L
    }
}
