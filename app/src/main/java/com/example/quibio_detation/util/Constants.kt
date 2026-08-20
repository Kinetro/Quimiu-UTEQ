package com.example.quibio_detation.util

object Constants {
    /** Umbral mínimo de confianza (0.0 - 1.0) para considerar válida una detección. */
    const val CONFIDENCE_THRESHOLD = 0.70f

    /** Cada cuántos milisegundos se analiza un frame de la cámara (para no saturar la CPU). */
    const val ANALYSIS_INTERVAL_MS = 500L

    /** TODO: ajustar al modelo disponible en la cuenta de OpenAI que se vaya a usar. */
    const val OPENAI_MODEL = "gpt-4o-mini"
}
