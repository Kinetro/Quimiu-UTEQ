package com.example.quibio_detation.viewmodel

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.quibio_detation.data.OpenAIRepository
import com.example.quibio_detation.ml.EquipmentClassifier
import com.example.quibio_detation.ml.ObjectLocator
import com.example.quibio_detation.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ChatUiState {
    data object Idle : ChatUiState()
    data object Loading : ChatUiState()
    data class Success(val answer: String) : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}

/**
 * Detección de un frame: caja real del objeto (en coordenadas de [imageWidth]
 * x [imageHeight], el bitmap ya orientado que se analizó) + etiqueta y
 * confianza del clasificador TFLite sobre el recorte de esa caja.
 */
data class TrackedDetection(
    val box: RectF,
    val imageWidth: Int,
    val imageHeight: Int,
    val label: String,
    val confidence: Float
)

class MainViewModel(
    private val classifier: EquipmentClassifier,
    private val objectLocator: ObjectLocator,
    private val repository: OpenAIRepository
) : ViewModel() {

    private val _detection = MutableStateFlow<TrackedDetection?>(null)
    val detection: StateFlow<TrackedDetection?> = _detection.asStateFlow()

    private val _chatState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    /**
     * Se llama desde el analyzer de CameraX (ya corre en un hilo de fondo,
     * ver MainActivity) con cada frame que se decide procesar. Primero ubica
     * el objeto en el frame (ML Kit) y luego clasifica solo ese recorte.
     */
    fun onFrameClassified(bitmap: Bitmap) {
        val box = objectLocator.locate(bitmap)
        if (box == null || box.width() <= 0 || box.height() <= 0) {
            _detection.value = null
            return
        }

        val safeBox = Rect(
            box.left.coerceIn(0, bitmap.width - 1),
            box.top.coerceIn(0, bitmap.height - 1),
            box.right.coerceIn(box.left + 1, bitmap.width),
            box.bottom.coerceIn(box.top + 1, bitmap.height)
        )
        val cropped = Bitmap.createBitmap(bitmap, safeBox.left, safeBox.top, safeBox.width(), safeBox.height())
        val result = classifier.classify(cropped)

        _detection.value = TrackedDetection(
            box = RectF(safeBox),
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            label = result.label,
            confidence = result.confidence
        )
    }

    fun askAboutDetectedEquipment() {
        val current = _detection.value ?: return
        if (current.confidence < Constants.CONFIDENCE_THRESHOLD) return

        viewModelScope.launch {
            _chatState.value = ChatUiState.Loading
            repository.askAboutEquipment(current.label)
                .onSuccess { answer -> _chatState.value = ChatUiState.Success(answer) }
                .onFailure { error -> _chatState.value = ChatUiState.Error(error.message ?: "Error desconocido") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        objectLocator.close()
    }

    class Factory(
        private val classifier: EquipmentClassifier,
        private val objectLocator: ObjectLocator,
        private val repository: OpenAIRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(classifier, objectLocator, repository) as T
        }
    }
}
