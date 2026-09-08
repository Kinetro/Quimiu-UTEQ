package com.example.quibio_detation.data

/**
 * Fallback sin IA: cuando Gemini Nano no está disponible (dispositivo no compatible, sin
 * AICore, o falló la generación), se busca la respuesta por coincidencia simple de palabras
 * clave dentro del texto del documento, en vez de dejar al usuario sin nada.
 */
object KeywordSearch {

    private val STOP_WORDS = setOf(
        "que", "cual", "cuales", "como", "para", "por", "del", "las", "los", "una", "uno",
        "con", "sin", "sus", "esta", "este", "esa", "ese", "sobre", "donde", "cuando",
        "hay", "hace", "tiene", "es", "el", "la", "de", "en", "y", "o", "a", "un"
    )

    /**
     * Divide [documentText] en párrafos y devuelve los que mejor coinciden con las palabras
     * significativas de [question]. Si ninguno coincide, se responde que no se encontró
     * información (nunca se vuelca el documento completo como "respuesta").
     */
    fun findAnswer(documentText: String, question: String): String {
        val paragraphs = splitParagraphs(documentText)
        if (paragraphs.isEmpty()) return NOT_FOUND_MESSAGE

        val keywords = extractKeywords(question)
        if (keywords.isEmpty()) return NOT_FOUND_MESSAGE

        val scored = scoreParagraphs(paragraphs, keywords)
        if (scored.isEmpty()) return NOT_FOUND_MESSAGE

        val bestParagraphs = scored.take(2).joinToString("\n\n") { it.first }
        return "No tengo IA disponible en este dispositivo; esto es lo que encontré en el documento " +
            "buscando \"${keywords.joinToString(", ")}\":\n\n$bestParagraphs"
    }

    /**
     * Recuperación de fragmentos estilo RAG: en vez de mandar el documento completo al LLM,
     * devuelve como máximo [maxFragments] párrafos (acotados a [maxChars] en total), eligiendo
     * los que mejor coinciden por palabras clave con [question]. Si no hay coincidencias (o la
     * pregunta no trae palabras significativas, como en la pregunta rápida por defecto), cae a
     * los primeros párrafos del documento en vez del texto entero.
     */
    fun retrieveFragments(
        documentText: String,
        question: String,
        maxFragments: Int = 3,
        maxChars: Int = 3000
    ): List<String> {
        val paragraphs = splitParagraphs(documentText)
        if (paragraphs.isEmpty()) return emptyList()

        val keywords = extractKeywords(question)
        val scored = if (keywords.isEmpty()) emptyList() else scoreParagraphs(paragraphs, keywords)
        val ranked = if (scored.isNotEmpty()) scored.map { it.first } else paragraphs

        val chosen = mutableListOf<String>()
        var usedChars = 0
        for (fragment in ranked) {
            if (chosen.size >= maxFragments || usedChars >= maxChars) break
            chosen += fragment
            usedChars += fragment.length
        }
        return chosen
    }

    private const val NOT_FOUND_MESSAGE =
        "No encontré una coincidencia para tu pregunta y no tengo IA disponible en este dispositivo."

    private fun splitParagraphs(documentText: String): List<String> =
        documentText.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !looksLikeTableOfContents(it) }

    /**
     * Los manuales extraídos de PDF suelen traer una tabla de contenidos con líneas de puntos
     * de relleno (ej. "1.0 Overview . . . . . . . . . . . 1"), que al no tener líneas en blanco
     * entre entradas queda como un solo párrafo gigante. Sin filtrarlo, ese párrafo "gana" el
     * fallback de "primeros párrafos del documento" (cuando no hay coincidencia de palabras
     * clave) y le tapa el paso al contenido real que sí responde la pregunta.
     */
    private fun looksLikeTableOfContents(paragraph: String): Boolean {
        if (paragraph.length < 120) return false
        val dotRatio = paragraph.count { it == '.' }.toDouble() / paragraph.length
        return dotRatio > 0.15
    }

    private fun scoreParagraphs(paragraphs: List<String>, keywords: List<String>): List<Pair<String, Int>> =
        paragraphs
            .map { paragraph -> paragraph to keywords.count { paragraph.contains(it, ignoreCase = true) } }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }

    private fun extractKeywords(question: String): List<String> {
        return Regex("[\\p{L}0-9]+")
            .findAll(question.lowercase())
            .map { it.value }
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinct()
            .toList()
    }
}
