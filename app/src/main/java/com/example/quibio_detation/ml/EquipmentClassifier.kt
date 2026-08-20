package com.example.quibio_detation.ml

import android.graphics.Bitmap

/**
 * Resultado de clasificar un frame de la cámara.
 * [confidence] va de 0.0 a 1.0 (se multiplica x100 solo para mostrarlo en la UI).
 */
data class DetectionResult(
    val label: String,
    val confidence: Float
)

/** Contrato común entre el clasificador TFLite real y el clasificador de prueba (Mock). */
interface EquipmentClassifier {
    fun classify(bitmap: Bitmap): DetectionResult
}
