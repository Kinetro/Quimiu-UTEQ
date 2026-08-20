package com.example.quibio_detation.network

import retrofit2.http.Body
import retrofit2.http.POST

interface OpenAIApiService {

    /**
     * Endpoint /v1/responses de OpenAI, usado con la herramienta "file_search"
     * para hacer RAG contra un vector store con la documentación de los equipos
     * del Laboratorio de Química-Bioquímica de la UTEQ.
     */
    @POST("responses")
    suspend fun askAboutEquipment(@Body request: ResponsesRequest): ResponsesApiResponse
}
