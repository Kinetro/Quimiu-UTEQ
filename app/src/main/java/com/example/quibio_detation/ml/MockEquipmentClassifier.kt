package com.example.quibio_detation.ml

import android.graphics.Bitmap
import kotlin.random.Random

/**
 * Clasificador de PRUEBA (no usa ningún modelo real).
 *
 * Se activa automáticamente cuando todavía no existe app/src/main/assets/model_unquant.tflite,
 * para poder probar todo el flujo de la app (overlay tipo YOLO, botón, llamada a OpenAI)
 * sin depender del modelo entrenado en Teachable Machine.
 *
 * TODO: una vez agreguen su model_unquant.tflite + labels.txt reales, este mock deja de
 * usarse automáticamente (ver ClassifierProvider.kt) y se puede borrar esta clase.
 */
class MockEquipmentClassifier(
    private val labels: List<String> = listOf("Equipo A", "Equipo B", "Sin deteccion")
) : EquipmentClassifier {

    private var frameCount = 0

    override fun classify(bitmap: Bitmap): DetectionResult {
        frameCount++
        // Cambia de "detección" cada cierto número de frames para simular una cámara real.
        val index = (frameCount / 30) % labels.size
        val confidence = Random.nextFloat() * 0.35f + 0.60f // entre 0.60 y 0.95
        return DetectionResult(label = labels[index], confidence = confidence)
    }
}
