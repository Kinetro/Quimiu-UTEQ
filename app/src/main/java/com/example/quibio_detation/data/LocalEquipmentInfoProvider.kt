package com.example.quibio_detation.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException

/**
 * Lee app/src/main/assets/info_equipos.json (mapa nombre_equipo -> texto informativo) para
 * usarlo como contexto de respaldo cuando el equipo todavía no tiene su ficha/manual completo
 * en app/src/main/assets/docs_equipos/ (ver EquipmentDocumentProvider y EquipmentQaRepository).
 */
class LocalEquipmentInfoProvider(context: Context) {

    private val appContext = context.applicationContext

    private val info: Map<String, String> by lazy { loadInfo() }

    fun getInfo(equipmentName: String): String? = info[equipmentName]

    private fun loadInfo(): Map<String, String> {
        return try {
            val json = appContext.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: IOException) {
            Log.w(TAG, "No se encontró $ASSET_FILE en assets", e)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "LocalEquipmentInfo"
        private const val ASSET_FILE = "info_equipos.json"
    }
}
