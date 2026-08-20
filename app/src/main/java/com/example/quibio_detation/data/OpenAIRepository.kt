package com.example.quibio_detation.data

import com.example.quibio_detation.BuildConfig
import com.example.quibio_detation.network.NetworkModule
import com.example.quibio_detation.network.OpenAIApiService
import com.example.quibio_detation.network.ResponsesRequest
import com.example.quibio_detation.network.Tool
import com.example.quibio_detation.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repositorio encargado de preguntar a la API de OpenAI (RAG con file_search)
 * sobre el equipo de laboratorio detectado por la cámara.
 *
 * TODO: BuildConfig.OPENAI_VECTOR_STORE_ID se define en local.properties.
 * Reemplazarlo por el vector_store_id real creado en el dashboard de OpenAI,
 * con los manuales/documentación de los equipos del Laboratorio de Química-Bioquímica.
 *
 * Respaldo (fallback): si falta la API key/vector store, o si la llamada HTTP falla
 * (sin conexión, timeout, 429 por falta de créditos, 500, etc.), se responde con la
 * información local de app/src/main/assets/info_equipos.json en vez de mostrar el
 * error crudo al usuario. Ver LocalEquipmentInfoProvider.
 */
class OpenAIRepository(
    private val localInfoProvider: LocalEquipmentInfoProvider,
    private val api: OpenAIApiService = NetworkModule.openAIApi
) {

    suspend fun askAboutEquipment(equipmentName: String): Result<String> = withContext(Dispatchers.IO) {
        if (BuildConfig.OPENAI_API_KEY.isBlank() || BuildConfig.OPENAI_VECTOR_STORE_ID.isBlank()) {
            return@withContext Result.success(localAnswer(equipmentName))
        }

        val prompt = "Explícame qué es, para qué sirve y cómo se usa correctamente " +
            "el equipo de laboratorio llamado \"$equipmentName\", en el contexto del " +
            "Laboratorio de Química-Bioquímica de la UTEQ."

        val request = ResponsesRequest(
            model = Constants.OPENAI_MODEL,
            input = prompt,
            tools = listOf(
                Tool(vectorStoreIds = listOf(BuildConfig.OPENAI_VECTOR_STORE_ID))
            )
        )

        try {
            val response = api.askAboutEquipment(request)
            Result.success(response.extractText())
        } catch (e: Exception) {
            Result.success(localAnswer(equipmentName))
        }
    }

    private fun localAnswer(equipmentName: String): String {
        return localInfoProvider.getInfo(equipmentName)
            ?: "No se encontró información local para este equipo."
    }
}
