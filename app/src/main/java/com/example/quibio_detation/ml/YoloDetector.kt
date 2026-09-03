package com.example.quibio_detation.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Detector YOLO (localización + clasificación en un solo paso) que reemplaza a
 * ObjectLocator (ML Kit) + TFLiteEquipmentClassifier (Teachable Machine).
 *
 * Espera un modelo exportado con `yolo export format=tflite` (YOLOv8/YOLO11):
 * entrada float32 [1, H, W, 3] (NHWC) o [1, 3, H, W] (NCHW, se detecta automáticamente
 * según la forma real del tensor de entrada) normalizada en [0,1], salida
 * [1, 4+numClases, numAnchors] (cx, cy, w, h, score_por_clase...), sin columna
 * de objectness separada. Si el modelo viene de YOLOv5, hay una columna extra
 * de objectness que habría que multiplicar por el score de clase antes del NMS.
 */
class YoloDetector(context: Context) : EquipmentDetector, Closeable {

    companion object {
        private const val MODEL_FILE = "yolo_model.tflite" // TODO: reemplazar por el modelo real exportado del entrenamiento YOLO
        private const val LABELS_FILE = "labels.txt" // TODO: clases reales del modelo (una por línea)
        private const val SCORE_THRESHOLD = 0.25f // umbral bajo previo al NMS; el umbral de UI (Constants.CONFIDENCE_THRESHOLD) se aplica después, en el ViewModel
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 10
        private val LETTERBOX_COLOR = Color.rgb(114, 114, 114) // gris estándar de letterbox de YOLO
    }

    private val interpreter = Interpreter(loadModelFile(context))
    private val labels = loadLabels(context)
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannelsFirst: Boolean

    init {
        val inputShape = interpreter.getInputTensor(0).shape()
        // NCHW: [1, 3, H, W] (algunos exports de Ultralytics vía onnx2tf mantienen este layout
        // en vez del NHWC [1, H, W, 3] habitual en TFLite). Se detecta por dónde cae el "3".
        inputChannelsFirst = inputShape[1] == 3
        if (inputChannelsFirst) {
            inputHeight = inputShape[2]
            inputWidth = inputShape[3]
        } else {
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
        }
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val letterboxed = letterbox(bitmap)

        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        interpreter.run(letterboxed.inputBuffer, output)

        val channelsFirst = outputShape[1] == 4 + labels.size
        val numAnchors = if (channelsFirst) outputShape[2] else outputShape[1]

        val candidates = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            var bestClass = -1
            var bestScore = 0f

            // cx, cy, w, h vienen normalizados en [0,1] (fracción de inputWidth/inputHeight),
            // no en píxeles absolutos del input 640x640 — hay que escalarlos primero.
            if (channelsFirst) {
                cx = output[0][0][i] * inputWidth; cy = output[0][1][i] * inputHeight
                w = output[0][2][i] * inputWidth; h = output[0][3][i] * inputHeight
                for (c in labels.indices) {
                    val score = output[0][4 + c][i]
                    if (score > bestScore) { bestScore = score; bestClass = c }
                }
            } else {
                val row = output[0][i]
                cx = row[0] * inputWidth; cy = row[1] * inputHeight
                w = row[2] * inputWidth; h = row[3] * inputHeight
                for (c in labels.indices) {
                    val score = row[4 + c]
                    if (score > bestScore) { bestScore = score; bestClass = c }
                }
            }

            if (bestClass < 0 || bestScore < SCORE_THRESHOLD) continue

            // (cx, cy, w, h) ya están en píxeles del input del modelo (con letterbox aplicado).
            // Se deshace el letterbox (padding + escala) para volver a coordenadas del bitmap original.
            val left = (cx - w / 2f - letterboxed.padX) / letterboxed.scale
            val top = (cy - h / 2f - letterboxed.padY) / letterboxed.scale
            val right = (cx + w / 2f - letterboxed.padX) / letterboxed.scale
            val bottom = (cy + h / 2f - letterboxed.padY) / letterboxed.scale

            candidates.add(
                Detection(
                    label = labels[bestClass],
                    confidence = bestScore,
                    rect = RectF(
                        left.coerceIn(0f, bitmap.width.toFloat()),
                        top.coerceIn(0f, bitmap.height.toFloat()),
                        right.coerceIn(0f, bitmap.width.toFloat()),
                        bottom.coerceIn(0f, bitmap.height.toFloat())
                    )
                )
            )
        }

        return nonMaxSuppression(candidates)
    }

    /** NMS por clase: entre cajas de la misma etiqueta que se superponen demasiado, se queda con la de mayor confianza. */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val pending = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()

        while (pending.isNotEmpty() && kept.size < MAX_DETECTIONS) {
            val best = pending.removeAt(0)
            kept.add(best)
            pending.removeAll { other -> other.label == best.label && iou(best.rect, other.rect) > IOU_THRESHOLD }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val union = a.width() * a.height() + b.width() * b.height() - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private data class LetterboxResult(val inputBuffer: ByteBuffer, val scale: Float, val padX: Float, val padY: Float)

    /**
     * Redimensiona [bitmap] a (inputWidth x inputHeight) preservando el aspect ratio
     * (letterbox, relleno gris en los bordes) y lo vuelca en un ByteBuffer float32
     * normalizado a [0,1], como espera el modelo YOLO exportado.
     */
    private fun letterbox(bitmap: Bitmap): LetterboxResult {
        val scale = min(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
        val padX = (inputWidth - bitmap.width * scale) / 2f
        val padY = (inputHeight - bitmap.height * scale) / 2f

        val letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(LETTERBOX_COLOR)
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(padX, padY)
            }
            drawBitmap(bitmap, matrix, null)
        }

        val buffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        letterboxed.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        if (inputChannelsFirst) {
            // Layout planar: todos los valores de R, luego todos los de G, luego todos los de B.
            for (shift in intArrayOf(16, 8, 0)) {
                for (pixel in pixels) {
                    buffer.putFloat(((pixel shr shift) and 0xFF) / 255f)
                }
            }
        } else {
            // Layout intercalado: R,G,B de cada píxel seguidos.
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                buffer.putFloat((pixel and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return LetterboxResult(buffer, scale, padX, padY)
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
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // Algunos exports (p. ej. Teachable Machine) escriben "0 Equipo A"; YOLO/Roboflow
                // suelen exportar solo "Equipo A". Se soportan ambos formatos.
                .map { line -> Regex("^\\d+\\s+").replace(line, "") }
                .toList()
        }
    }

    override fun close() = interpreter.close()
}
