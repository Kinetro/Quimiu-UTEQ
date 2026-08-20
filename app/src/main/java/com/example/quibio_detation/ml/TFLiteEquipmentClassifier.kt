package com.example.quibio_detation.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Clasificador que carga el modelo TensorFlow Lite exportado desde Teachable Machine
 * (proyecto de tipo "Image Project") y clasifica el frame que ve la cámara.
 *
 * ============================================================================
 *  REEMPLAZAR AQUÍ CON EL MODELO REAL:
 *   1) app/src/main/assets/model_unquant.tflite -> modelo entrenado en Teachable Machine
 *   2) app/src/main/assets/labels.txt    -> una etiqueta por línea, en el mismo
 *                                          orden que las clases del modelo
 *
 *  Mientras estos archivos no existan (o no coincidan con el formato esperado),
 *  la app usa automáticamente MockEquipmentClassifier para poder probar el resto
 *  del flujo. Ver ClassifierProvider.kt.
 * ============================================================================
 */
class TFLiteEquipmentClassifier(context: Context) : EquipmentClassifier {

    companion object {
        private const val MODEL_FILE = "model_unquant.tflite" // Modelo real de Teachable Machine (16 clases)
        private const val LABELS_FILE = "labels.txt"   // TODO: clases reales del modelo
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int

    init {
        interpreter = Interpreter(loadModelFile(context))
        labels = loadLabels(context)

        val inputShape = interpreter.getInputTensor(0).shape() // [1, height, width, 3]
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.declaredLength
            )
        }
    }

    @Throws(IOException::class)
    private fun loadLabels(context: Context): List<String> {
        return context.assets.open(LABELS_FILE).bufferedReader().useLines { lines ->
            lines
                // Teachable Machine suele exportar "0 Equipo A" -> nos quedamos solo con el nombre
                .map { line -> line.trim().substringAfter(' ', line.trim()).trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }

    override fun classify(bitmap: Bitmap): DetectionResult {
        val tensorImage = TensorImage(DataType.FLOAT32).apply { load(bitmap) }

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
            // Teachable Machine (modelo flotante) espera valores normalizados en [0, 1].
            // TODO: si tu modelo real usa otro rango (p. ej. [-1, 1]), ajustar mean/std aquí.
            .add(NormalizeOp(0f, 255f))
            .build()

        val processedImage = imageProcessor.process(tensorImage)

        val outputBuffer = Array(1) { FloatArray(labels.size) }
        interpreter.run(processedImage.buffer, outputBuffer)

        val probabilities = outputBuffer[0]
        var bestIndex = 0
        for (i in probabilities.indices) {
            if (probabilities[i] > probabilities[bestIndex]) bestIndex = i
        }

        return DetectionResult(
            label = labels.getOrElse(bestIndex) { "Desconocido" },
            confidence = probabilities[bestIndex]
        )
    }

    fun close() {
        interpreter.close()
    }
}
