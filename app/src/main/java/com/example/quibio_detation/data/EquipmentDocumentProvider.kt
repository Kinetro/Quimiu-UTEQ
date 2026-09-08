package com.example.quibio_detation.data

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Carga la ficha técnica / manual de cada equipo desde
 * app/src/main/assets/docs_equipos/<NombreClaseTFLite>.txt
 *
 * *** DÓNDE AGREGAR LOS DOCUMENTOS DE CADA EQUIPO ***
 * 1. Extraer el texto de cada PDF de "Documentos de aparatos de laboratorio/" (por ejemplo,
 *    con PdfBox-Android o iText) y guardarlo como un .txt de texto plano (UTF-8).
 * 2. Nombrar el archivo EXACTAMENTE igual a la etiqueta del modelo TFLite, tal como aparece
 *    en app/src/main/assets/labels.txt (mismas claves que ya usa info_equipos.json), por
 *    ejemplo: Contador_Colonias_CC1.txt, Autoclave.txt, Balanza_Analitica_Ohaus.txt, etc.
 * 3. Colocarlo en app/src/main/assets/docs_equipos/. Ver esa carpeta para placeholders con
 *    el nombre correcto ya creados para las 20 clases actuales del modelo.
 * 4. Si un equipo no tiene .txt todavía, EquipmentQaRepository usa automáticamente
 *    info_equipos.json (LocalEquipmentInfoProvider) como contexto de respaldo.
 */
class EquipmentDocumentProvider(context: Context) {

    private val appContext = context.applicationContext

    /** Cache simple en memoria: cada documento se lee de assets una sola vez por sesión. */
    private val cache = mutableMapOf<String, String?>()

    /**
     * @return el texto COMPLETO (sin recortar) del documento del equipo, o null si no existe
     *   el .txt para esta clase. El recorte al límite de contexto de Gemini Nano se hace
     *   más adelante, solo sobre lo que se envía al modelo (ver EquipmentQaRepository), para
     *   que el fallback de "mostrar el documento completo para lectura manual" siga siendo
     *   realmente completo.
     */
    fun getDocumentText(equipmentName: String): String? {
        return cache.getOrPut(equipmentName) { loadDocument(equipmentName) }
    }

    private fun loadDocument(equipmentName: String): String? {
        val path = "$ASSETS_DIR/$equipmentName.txt"
        return try {
            appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .trim()
                .ifEmpty { null }
        } catch (e: IOException) {
            Log.i(TAG, "Sin documento .txt para \"$equipmentName\" en $ASSETS_DIR/ (se usará el fallback local)")
            null
        }
    }

    companion object {
        private const val TAG = "EquipmentDocumentProvider"
        private const val ASSETS_DIR = "docs_equipos"
    }
}
