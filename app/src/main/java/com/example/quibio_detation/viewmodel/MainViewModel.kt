package com.example.quibio_detation.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.quibio_detation.data.OpenAIRepository
import com.example.quibio_detation.ml.Detection
import com.example.quibio_detation.ml.EquipmentDetector
import com.example.quibio_detation.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

data class ChatMessage(val isUser: Boolean, val text: String)

/**
 * Todas las detecciones de un frame (el bitmap ya orientado que se analizó),
 * en coordenadas de [imageWidth] x [imageHeight].
 */
data class FrameDetections(
    val imageWidth: Int,
    val imageHeight: Int,
    val boxes: List<Detection>
)

class MainViewModel(
    private val detector: EquipmentDetector,
    private val repository: OpenAIRepository
) : ViewModel() {

    private val _frameDetections = MutableStateFlow<FrameDetections?>(null)
    val frameDetections: StateFlow<FrameDetections?> = _frameDetections.asStateFlow()

    private val _selectedDetection = MutableStateFlow<Detection?>(null)
    val selectedDetection: StateFlow<Detection?> = _selectedDetection.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** Id de la última respuesta de OpenAI, para encadenar la próxima pregunta en la misma conversación. */
    private var lastResponseId: String? = null

    /**
     * Se llama desde el analyzer de CameraX (ya corre en un hilo de fondo,
     * ver MainActivity) con cada frame que se decide procesar. El detector YOLO
     * localiza y clasifica todos los objetos del frame en un solo paso.
     */
    fun onFrameAnalyzed(bitmap: Bitmap) {
        val detections = detector.detect(bitmap)
            .filter { it.confidence >= Constants.CONFIDENCE_THRESHOLD }

        _frameDetections.value = FrameDetections(bitmap.width, bitmap.height, detections)

        // Si la detección seleccionada ya no aparece en este frame, se limpia la selección.
        val current = _selectedDetection.value
        if (current != null && detections.none { it.label == current.label }) {
            _selectedDetection.value = null
        }
    }

    /** Se llama al tocar una caja del overlay o un chip de la lista de equipos detectados. */
    fun selectDetection(detection: Detection) {
        if (_selectedDetection.value?.label != detection.label) {
            // Cambiar de equipo empieza una conversación nueva.
            _chatMessages.value = emptyList()
            lastResponseId = null
        }
        _selectedDetection.value = detection
    }

    /** Botón de acceso rápido: pregunta fija "qué es y para qué sirve este equipo". */
    fun askAboutDetectedEquipment() {
        val current = _selectedDetection.value ?: return
        send(current.label, question = null)
    }

    /** Pregunta libre escrita por el usuario en el chat. */
    fun sendUserMessage(text: String) {
        val current = _selectedDetection.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        send(current.label, question = trimmed)
    }

    private fun send(equipmentLabel: String, question: String?) {
        if (question != null) {
            _chatMessages.value = _chatMessages.value + ChatMessage(isUser = true, text = question)
        }

        viewModelScope.launch {
            _isSending.value = true
            repository.ask(equipmentLabel, question, lastResponseId)
                .onSuccess { reply ->
                    lastResponseId = reply.responseId
                    _chatMessages.value = _chatMessages.value + ChatMessage(isUser = false, text = reply.text)
                }
                .onFailure { error ->
                    _chatMessages.value = _chatMessages.value +
                        ChatMessage(isUser = false, text = "Error: ${error.message ?: "desconocido"}")
                }
            _isSending.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        (detector as? Closeable)?.close()
    }

    class Factory(
        private val detector: EquipmentDetector,
        private val repository: OpenAIRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(detector, repository) as T
        }
    }
}
