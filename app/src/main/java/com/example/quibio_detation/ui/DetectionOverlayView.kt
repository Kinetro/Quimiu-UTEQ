package com.example.quibio_detation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Dibuja un recuadro estilo "detección" (similar a YOLO) siguiendo la caja
 * real del objeto localizado en el frame (ver ObjectLocator + MainViewModel),
 * junto con la etiqueta y el porcentaje de confianza del clasificador TFLite.
 *
 * [box] llega en coordenadas de píxeles del bitmap analizado ([imageWidth] x
 * [imageHeight]); acá se remapea al tamaño real de esta vista replicando el
 * escalado FILL_CENTER que usa PreviewView por defecto (la imagen se agranda
 * para llenar la vista, recortando el sobrante, centrada).
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 42f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private var box: RectF? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var label: String? = null
    private var confidence: Float = 0f
    private var visible = false

    fun showDetection(box: RectF, imageWidth: Int, imageHeight: Int, label: String, confidence: Float) {
        this.box = box
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.label = label
        this.confidence = confidence
        this.visible = true
        invalidate()
    }

    fun clear() {
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentBox = box
        val currentLabel = label
        if (!visible || currentBox == null || currentLabel == null || imageWidth == 0 || imageHeight == 0) return

        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        val viewBox = RectF(
            currentBox.left * scale + offsetX,
            currentBox.top * scale + offsetY,
            currentBox.right * scale + offsetX,
            currentBox.bottom * scale + offsetY
        )
        canvas.drawRect(viewBox, boxPaint)

        val text = "$currentLabel  ${(confidence * 100).toInt()}%"
        val textWidth = textPaint.measureText(text)
        val labelTop = (viewBox.top - 60f).coerceAtLeast(0f)
        val labelBackground = RectF(viewBox.left, labelTop, viewBox.left + textWidth + 24f, labelTop + 60f)
        canvas.drawRect(labelBackground, textBackgroundPaint)
        canvas.drawText(text, viewBox.left + 12f, labelTop + 44f, textPaint)
    }
}
