package com.example.quibio_detation.util

object Constants {
    /** Umbral mínimo de confianza (0.0 - 1.0) para considerar válida una detección. */
    const val CONFIDENCE_THRESHOLD = 0.70f

    /**
     * Cada cuántos milisegundos se analiza un frame de la cámara (para no saturar la CPU).
     * Se bajó de 500ms a 200ms para que el recuadro de detección siga al objeto de forma
     * fluida en vez de "saltar" cada medio segundo. Si el dispositivo sufre de lag, subir
     * este valor.
     */
    const val ANALYSIS_INTERVAL_MS = 200L

    /** TODO: ajustar al modelo disponible en la cuenta de OpenAI que se vaya a usar. */
    const val OPENAI_MODEL = "gpt-4o-mini"
}
