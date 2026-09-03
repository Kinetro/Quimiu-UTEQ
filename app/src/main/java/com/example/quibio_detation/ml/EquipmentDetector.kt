package com.example.quibio_detation.ml

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Resultado de una detección YOLO: clase, confianza (0.0-1.0) y caja en
 * píxeles del bitmap analizado (localización + clasificación en un solo paso).
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val rect: RectF
)

/** Contrato común entre el detector YOLO real y el detector de prueba (Mock). */
interface EquipmentDetector {
    fun detect(bitmap: Bitmap): List<Detection>
}
