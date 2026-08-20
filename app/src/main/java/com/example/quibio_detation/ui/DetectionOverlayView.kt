package com.example.quibio_detation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Dibuja un recuadro estilo "detección" (similar a YOLO) con la etiqueta y el
 * porcentaje de confianza del equipo detectado.
 *
 * NOTA: el modelo de Teachable Machine usado aquí es de CLASIFICACIÓN (clasifica
 * la imagen completa del frame), no de detección de objetos con coordenadas reales.
 * Por eso el recuadro se dibuja simulando el área central del frame.
 * TODO: si en el futuro usan un modelo de detección real (YOLO, EfficientDet, etc.)
 * con bounding boxes propios, reemplazar showDetection() para usar las coordenadas
 * que devuelva ese modelo en lugar del recuadro simulado.
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

    private var label: String? = null
    private var confidence: Float = 0f
    private var visible = false

    fun showDetection(label: String, confidence: Float) {
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
        val currentLabel = label
        if (!visible || currentLabel == null) return

        // Recuadro simulado sobre el área central del frame (ver nota de la clase).
        val margin = width * 0.1f
        val box = RectF(margin, height * 0.2f, width - margin, height * 0.8f)
        canvas.drawRect(box, boxPaint)

        val text = "$currentLabel  ${(confidence * 100).toInt()}%"
        val textWidth = textPaint.measureText(text)
        val labelBackground = RectF(box.left, box.top - 60f, box.left + textWidth + 24f, box.top)
        canvas.drawRect(labelBackground, textBackgroundPaint)
        canvas.drawText(text, box.left + 12f, box.top - 16f, textPaint)
    }
}
