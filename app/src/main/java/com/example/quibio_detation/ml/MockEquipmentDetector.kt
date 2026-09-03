package com.example.quibio_detation.ml

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.random.Random

/**
 * Detector de PRUEBA (no usa ningún modelo real).
 *
 * Se activa automáticamente cuando todavía no existe app/src/main/assets/yolo_model.tflite,
 * para poder probar el overlay multi-caja y el flujo de selección (tap) → chat RAG sin
 * depender del modelo YOLO entrenado.
 *
 * TODO: una vez agreguen su yolo_model.tflite + labels.txt reales, este mock deja de
 * usarse automáticamente (ver DetectorProvider.kt) y se puede borrar esta clase.
 */
class MockEquipmentDetector(
    private val labels: List<String> = listOf("Equipo A", "Equipo B")
) : EquipmentDetector {

    private var frameCount = 0

    override fun detect(bitmap: Bitmap): List<Detection> {
        frameCount++
        // Cambia de "detección" cada cierto número de frames para simular una cámara real.
        val index = (frameCount / 30) % labels.size
        val confidence = Random.nextFloat() * 0.35f + 0.60f // entre 0.60 y 0.95

        val boxWidth = bitmap.width * 0.4f
        val boxHeight = bitmap.height * 0.4f
        val left = bitmap.width * 0.3f
        val top = bitmap.height * 0.3f

        return listOf(
            Detection(
                label = labels[index],
                confidence = confidence,
                rect = RectF(left, top, left + boxWidth, top + boxHeight)
            )
        )
    }
}
