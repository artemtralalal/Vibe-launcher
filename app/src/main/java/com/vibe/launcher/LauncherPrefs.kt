package com.vibe.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    var flagshipAnimations: Boolean
        get() = prefs.getBoolean(KEY_FLAGSHIP, true)
        set(value) = prefs.edit().putBoolean(KEY_FLAGSHIP, value).apply()

    fun saveGridOrder(keys: List<String>) {
        prefs.edit().putString(KEY_GRID, JSONArray(keys).toString()).apply()
    }

    fun saveDockOrder(keys: List<String>) {
        prefs.edit().putString(KEY_DOCK, JSONArray(keys).toString()).apply()
    }

    fun loadGridOrder(): List<String> = loadList(KEY_GRID)
    fun loadDockOrder(): List<String> = loadList(KEY_DOCK)

    private fun loadList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY_FLAGSHIP = "flagship_animations"
        private const val KEY_GRID = "grid_order"
        private const val KEY_DOCK = "dock_order"
    }
}
