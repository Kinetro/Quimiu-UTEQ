package com.example.quibio_detation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.quibio_detation.ml.Detection
import kotlin.math.min

/**
 * Dibuja las cajas de detección con diseño de alta tecnología (estilo visor inteligente AR):
 * esquinas estilizadas, relleno sutil translúcido, y etiquetas flotantes glassmorphism
 * con nombres formateados y porcentajes nítidos.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDetectionTapped: ((Detection) -> Unit)? = null

    // Caja normal: Esmeralda moderno
    private val boxColor = Color.parseColor("#10B981")
    private val boxFillColor = Color.parseColor("#2610B981") // ~15% opacidad

    // Caja seleccionada: Ámbar brillante
    private val selectedBoxColor = Color.parseColor("#F59E0B")
    private val selectedBoxFillColor = Color.parseColor("#38F59E0B") // ~22% opacidad

    private val boxStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val boxFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerBracketPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val badgeBackgroundPaint = Paint().apply {
        color = Color.parseColor("#E60F172A") // Slate oscuro con 90% opacidad
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val badgeBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val confidenceBadgePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val confidenceTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
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

            val strokeColor = if (isSelected) selectedBoxColor else boxColor
            val fillColor = if (isSelected) selectedBoxFillColor else boxFillColor

            // 1. Relleno suave translúcido para destacar el objeto
            boxFillPaint.color = fillColor
            canvas.drawRoundRect(viewBox, 18f, 18f, boxFillPaint)

            // 2. Contorno redondeado sutil
            boxStrokePaint.color = strokeColor
            canvas.drawRoundRect(viewBox, 18f, 18f, boxStrokePaint)

            // 3. Brackets en las 4 esquinas estilo visor inteligente
            drawCornerBrackets(canvas, viewBox, strokeColor)

            // 4. Etiqueta flotante superior (Badge Glassmorphism)
            drawDetectionBadge(canvas, viewBox, detection, isSelected, strokeColor)
        }
    }

    private fun drawCornerBrackets(canvas: Canvas, rect: RectF, color: Int) {
        cornerBracketPaint.color = color
        val bracketLen = min(36f, min(rect.width(), rect.height()) / 4f)

        // Superior Izquierda
        canvas.drawLine(rect.left, rect.top, rect.left + bracketLen, rect.top, cornerBracketPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + bracketLen, cornerBracketPaint)

        // Superior Derecha
        canvas.drawLine(rect.right - bracketLen, rect.top, rect.right, rect.top, cornerBracketPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + bracketLen, cornerBracketPaint)

        // Inferior Izquierda
        canvas.drawLine(rect.left, rect.bottom, rect.left + bracketLen, rect.bottom, cornerBracketPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - bracketLen, cornerBracketPaint)

        // Inferior Derecha
        canvas.drawLine(rect.right - bracketLen, rect.bottom, rect.right, rect.bottom, cornerBracketPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - bracketLen, cornerBracketPaint)
    }

    private fun drawDetectionBadge(
        canvas: Canvas,
        viewBox: RectF,
        detection: Detection,
        isSelected: Boolean,
        accentColor: Int
    ) {
        val labelText = (if (isSelected) "✓ " else "") + formatEquipmentLabel(detection.label)
        val confText = "${(detection.confidence * 100).toInt()}%"

        val labelWidth = textLabelPaint.measureText(labelText)
        val confWidth = confidenceTextPaint.measureText(confText)

        val badgeHeight = 58f
        val paddingH = 16f
        val confPaddingH = 12f
        val confBadgeWidth = confWidth + confPaddingH * 2
        val totalBadgeWidth = labelWidth + confBadgeWidth + paddingH * 3

        val badgeTop = (viewBox.top - badgeHeight - 14f).coerceAtLeast(8f)
        val badgeLeft = viewBox.left.coerceAtLeast(8f)
        val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + totalBadgeWidth, badgeTop + badgeHeight)

        // Fondo oscuro glassmorphism del badge
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeBackgroundPaint)

        // Borde fino del color de acento
        badgeBorderPaint.color = accentColor
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeBorderPaint)

        // Texto del nombre
        val textY = badgeTop + (badgeHeight / 2f) - ((textLabelPaint.descent() + textLabelPaint.ascent()) / 2f)
        canvas.drawText(labelText, badgeLeft + paddingH, textY, textLabelPaint)

        // Insignia pequeña con el % de confianza
        val confLeft = badgeLeft + paddingH + labelWidth + paddingH
        val confTop = badgeTop + 10f
        val confBottom = badgeTop + badgeHeight - 10f
        val confRect = RectF(confLeft, confTop, confLeft + confBadgeWidth, confBottom)

        confidenceBadgePaint.color = accentColor
        canvas.drawRoundRect(confRect, 10f, 10f, confidenceBadgePaint)

        val confTextY = confTop + ((confBottom - confTop) / 2f) - ((confidenceTextPaint.descent() + confidenceTextPaint.ascent()) / 2f)
        confidenceTextPaint.color = if (isSelected) Color.BLACK else Color.WHITE
        canvas.drawText(confTextTextCentered(confText, confRect.centerX()), confRect.centerX() - (confWidth / 2f), confTextY, confidenceTextPaint)
    }

    private fun confTextTextCentered(text: String, centerX: Float): String = text

    private fun formatEquipmentLabel(rawLabel: String): String {
        return when (rawLabel) {
            "Memmert_Unidad2" -> "Memmert (U2)"
            "Agitador_Orbital_DLAB" -> "Agitador Orbital DLAB"
            "Balanza_Analitica_Ohaus" -> "Balanza Analítica"
            "Balanza_Granataria_Ohaus" -> "Balanza Granataria"
            "Camara_Extractora_Biobase" -> "Cámara Extractora"
            "Centrifuga_Ohaus_Frontier" -> "Centrífuga Ohaus"
            "Contador_Colonias_CC1" -> "Contador Colonias CC1"
            "Destilador_agua" -> "Destilador de Agua"
            "Estufa_Memmert" -> "Estufa Memmert"
            "Horno_Secado_Biobase" -> "Horno Secado Biobase"
            "Incubadora" -> "Incubadora"
            "Microscopio_Binocular" -> "Microscopio Binocular"
            "Microscopio_camara" -> "Microscopio Cámara"
            "Microscopio_estereo" -> "Microscopio Estéreo"
            "Phmetro_Ohaus" -> "pHmetro Ohaus"
            "Plancha_Agitacion_Cimarec" -> "Plancha Agitación"
            "Sistema_Rotaevaporacion" -> "Rotaevaporación"
            "Vortex_Mixer_LabNet" -> "Vortex Mixer"
            "Autoclave" -> "Autoclave"
            else -> rawLabel.replace("_", " ")
        }
    }
}

