package com.example.quibio_detation.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.quibio_detation.data.OpenAIRepository
import com.example.quibio_detation.ml.DetectionResult
import com.example.quibio_detation.ml.EquipmentClassifier
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

class MainViewModel(
    private val classifier: EquipmentClassifier,
    private val repository: OpenAIRepository
) : ViewModel() {

    private val _detection = MutableStateFlow<DetectionResult?>(null)
    val detection: StateFlow<DetectionResult?> = _detection.asStateFlow()

    private val _chatState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    /**
     * Se llama desde el analyzer de CameraX (ya corre en un hilo de fondo,
     * ver MainActivity) con cada frame que se decide procesar.
     */
    fun onFrameClassified(bitmap: Bitmap) {
        _detection.value = classifier.classify(bitmap)
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

    class Factory(
        private val classifier: EquipmentClassifier,
        private val repository: OpenAIRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(classifier, repository) as T
        }
    }
}
