package com.example.quibio_detation.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * Localiza la posición real del objeto más prominente del frame usando el
 * detector genérico on-device de ML Kit (STREAM_MODE con tracking entre
 * frames), para que el recuadro de UI pueda seguir al objeto en vez de
 * quedarse fijo en el centro de la pantalla.
 *
 * No clasifica: la etiqueta específica (vaso, matraz, etc.) la sigue poniendo
 * [EquipmentClassifier] sobre el recorte de la región que devuelve [locate].
 */
class ObjectLocator {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .build()
    )

    /**
     * Bloqueante: se debe llamar desde un hilo de fondo (ver cameraExecutor
     * en MainActivity), nunca desde el hilo principal.
     */
    fun locate(bitmap: Bitmap): Rect? {
        val objects = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        return objects.firstOrNull()?.boundingBox
    }

    fun close() = detector.close()
}
