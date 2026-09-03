package com.example.quibio_detation.ml

import android.content.Context
import android.util.Log
import java.io.IOException

object DetectorProvider {

    private const val TAG = "DetectorProvider"

    /**
     * Intenta cargar el modelo YOLO real (assets/yolo_model.tflite + assets/labels.txt).
     * Si no existen todavía, cae automáticamente al detector de prueba (Mock)
     * para no bloquear el desarrollo del resto de la app.
     */
    fun create(context: Context): EquipmentDetector {
        return try {
            YoloDetector(context)
        } catch (e: IOException) {
            Log.w(TAG, "No se encontró yolo_model.tflite/labels.txt en assets, usando MockEquipmentDetector", e)
            MockEquipmentDetector()
        }
    }
}
