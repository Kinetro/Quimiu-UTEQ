package com.example.quibio_detation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.quibio_detation.ml.Detection

/**
 * Dibuja las cajas de detección (estilo YOLO) de todos los objetos localizados
 * en el frame, con etiqueta + confianza, y permite seleccionar una tocándola:
 * dispara [onDetectionTapped] con la detección bajo el punto tocado, que el
 * caller (MainActivity) reenvía a MainViewModel.selectDetection para habilitar
 * el flujo de "preguntar al chat RAG" sobre ese equipo puntual.
 *
 * Las cajas llegan en coordenadas de píxeles del bitmap analizado ([imageWidth] x
 * [imageHeight]); acá se remapean al tamaño real de esta vista replicando el
 * escalado FILL_CENTER que usa PreviewView por defecto (la imagen se agranda
 * para llenar la vista, recortando el sobrante, centrada).
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDetectionTapped: ((Detection) -> Unit)? = null

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val selectedBoxPaint = Paint().apply {
        color = Color.parseColor("#FFC400")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 42f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private var boxes: List<Detection> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0
    private var selected: Detection? = null

    fun showDetections(boxes: List<Detection>, imageWidth: Int, imageHeight: Int) {
        this.boxes = boxes
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        if (boxes.none { it.label == selected?.label }) {
            selected = null
        }
        invalidate()
    }

    /** Resalta con otro color la detección seleccionada (ver MainViewModel.selectedDetection). */
    fun setSelected(detection: Detection?) {
        selected = detection
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        selected = null
        invalidate()
    }

    private fun imageToViewRect(box: RectF): RectF {
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f
        return RectF(
            box.left * scale + offsetX,
            box.top * scale + offsetY,
            box.right * scale + offsetX,
            box.bottom * scale + offsetY
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (imageWidth > 0 && imageHeight > 0) {
                    val tapped = boxes.firstOrNull { imageToViewRect(it.rect).contains(event.x, event.y) }
                    if (tapped != null) onDetectionTapped?.invoke(tapped)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty() || imageWidth == 0 || imageHeight == 0) return

        for (detection in boxes) {
            val viewBox = imageToViewRect(detection.rect)
            val isSelected = detection.label == selected?.label
            val paint = if (isSelected) selectedBoxPaint else boxPaint
            canvas.drawRect(viewBox, paint)

            val text = "${detection.label}  ${(detection.confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(text)
            val labelTop = (viewBox.top - 60f).coerceAtLeast(0f)
            val labelBackground = RectF(viewBox.left, labelTop, viewBox.left + textWidth + 24f, labelTop + 60f)
            textBackgroundPaint.color = paint.color
            canvas.drawRect(labelBackground, textBackgroundPaint)
            canvas.drawText(text, viewBox.left + 12f, labelTop + 44f, textPaint)
        }
    }
}
