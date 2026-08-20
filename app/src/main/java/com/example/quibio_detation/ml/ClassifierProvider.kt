package com.example.quibio_detation.ml

import android.content.Context
import android.util.Log
import java.io.IOException

object ClassifierProvider {

    private const val TAG = "ClassifierProvider"

    /**
     * Intenta cargar el modelo TFLite real (assets/model_unquant.tflite + assets/labels.txt).
     * Si no existen todavía, cae automáticamente al clasificador de prueba (Mock)
     * para no bloquear el desarrollo del resto de la app.
     */
    fun create(context: Context): EquipmentClassifier {
        return try {
            TFLiteEquipmentClassifier(context)
        } catch (e: IOException) {
            Log.w(TAG, "No se encontró model.tflite/labels.txt en assets, usando MockEquipmentClassifier", e)
            MockEquipmentClassifier()
        }
    }
}
