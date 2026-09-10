package com.example.quibio_detation.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.quibio_detation.data.EquipmentQaRepository
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
    private val repository: EquipmentQaRepository
) : ViewModel() {

    private val _frameDetections = MutableStateFlow<FrameDetections?>(null)
    val frameDetections: StateFlow<FrameDetections?> = _frameDetections.asStateFlow()

    private val _selectedDetection = MutableStateFlow<Detection?>(null)
    val selectedDetection: StateFlow<Detection?> = _selectedDetection.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /**
     * Progreso (0-100) de la descarga de Gemini Nano la primera vez que se usa en el
     * dispositivo. null cuando no hay ninguna descarga en curso.
     */
    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    /**
     * Se llama desde el analyzer de CameraX (ya corre en un hilo de fondo,
     * ver MainActivity) con cada frame que se decide procesar. El detector YOLO
     * localiza y clasifica todos los objetos del frame en un solo paso.
     */
    fun onFrameAnalyzed(bitmap: Bitmap) {
        val detections = detector.detect(bitmap)
            .filter { it.confidence >= Constants.CONFIDENCE_THRESHOLD }

        _frameDetections.value = FrameDetections(bitmap.width, bitmap.height, detections)

        // La selección NO se borra aunque el equipo deje de detectarse en un frame (movimiento,
        // desenfoque, se sale del cuadro): una vez que el usuario elige un equipo, sigue
        // seleccionado -y se puede seguir preguntando, incluso por voz- hasta que elija otro.
    }

    /** Se llama al tocar una caja del overlay o un chip de la lista de equipos detectados. */
    fun selectDetection(detection: Detection) {
        if (_selectedDetection.value?.label != detection.label) {
            // Cambiar de equipo empieza una conversación nueva.
            _chatMessages.value = emptyList()
        }
        _selectedDetection.value = detection
    }

    /** Pregunta libre escrita por el usuario en el chat. */
    fun sendUserMessage(text: String) {
        val current = _selectedDetection.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        send(current.label, question = trimmed)
    }

    private fun send(equipmentLabel: String, question: String) {
        if (_isSending.value) return // evita disparar 2 consultas si se toca/enviar dos veces seguidas
        _chatMessages.value = _chatMessages.value + ChatMessage(isUser = true, text = question)

        viewModelScope.launch {
            _isSending.value = true
            repository.ask(
                equipmentName = equipmentLabel,
                question = question,
                onDownloadProgress = { percent -> _downloadProgress.value = percent }
            )
                .onSuccess { reply ->
                    _chatMessages.value = _chatMessages.value + ChatMessage(isUser = false, text = reply.text)
                }
                .onFailure { error ->
                    _chatMessages.value = _chatMessages.value +
                        ChatMessage(isUser = false, text = "Error: ${error.message ?: "desconocido"}")
                }
            _downloadProgress.value = null
            _isSending.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        (detector as? Closeable)?.close()
    }

    class Factory(
        private val detector: EquipmentDetector,
        private val repository: EquipmentQaRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(detector, repository) as T
        }
    }
}
