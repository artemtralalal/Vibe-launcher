package com.vibe.launcher

import android.content.Context
import org.json.JSONArray

class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    var flagshipAnimations: Boolean
        get() = prefs.getBoolean(KEY_FLAGSHIP, true)
        set(value) = prefs.edit().putBoolean(KEY_FLAGSHIP, value).apply()

    // Animation tuning (percent/scale factors kept simple)
    var openDuration: Int
        get() = prefs.getInt("open_duration", 380)
        set(v) = prefs.edit().putInt("open_duration", v).apply()
    var closeDuration: Int
        get() = prefs.getInt("close_duration", 340)
        set(v) = prefs.edit().putInt("close_duration", v).apply()
    var openScale: Float
        get() = prefs.getFloat("open_scale", 0.85f)
        set(v) = prefs.edit().putFloat("open_scale", v).apply()
    var closeScale: Float
        get() = prefs.getFloat("close_scale", 0.88f)
        set(v) = prefs.edit().putFloat("close_scale", v).apply()
    var openTranslateX: Float
        get() = prefs.getFloat("open_tx", 0f)
        set(v) = prefs.edit().putFloat("open_tx", v).apply()
    var openTranslateY: Float
        get() = prefs.getFloat("open_ty", 0f)
        set(v) = prefs.edit().putFloat("open_ty", v).apply()
    var closeTranslateX: Float
        get() = prefs.getFloat("close_tx", 0f)
        set(v) = prefs.edit().putFloat("close_tx", v).apply()
    var closeTranslateY: Float
        get() = prefs.getFloat("close_ty", -40f)
        set(v) = prefs.edit().putFloat("close_ty", v).apply()
    var openOvershoot: Float
        get() = prefs.getFloat("open_overshoot", 0.6f)
        set(v) = prefs.edit().putFloat("open_overshoot", v).apply()
    var closeOvershoot: Float
        get() = prefs.getFloat("close_overshoot", 0.4f)
        set(v) = prefs.edit().putFloat("close_overshoot", v).apply()
    var blurEnabled: Boolean
        get() = prefs.getBoolean("blur_enabled", true)
        set(v) = prefs.edit().putBoolean("blur_enabled", v).apply()
    var blurRadius: Float
        get() = prefs.getFloat("blur_radius", 24f)
        set(v) = prefs.edit().putFloat("blur_radius", v).apply()
    var iconPackPackage: String
        get() = prefs.getString("icon_pack", "") ?: ""
        set(v) = prefs.edit().putString("icon_pack", v).apply()

    var welcomeDone: Boolean
        get() = prefs.getBoolean("welcome_done", false)
        set(v) = prefs.edit().putBoolean("welcome_done", v).apply()

    var iconShape: String
        get() = prefs.getString("icon_shape", "circle") ?: "circle"
        set(v) = prefs.edit().putString("icon_shape", v).apply()

    var unlockAnim: String
        get() = prefs.getString("unlock_anim", "pixel") ?: "pixel"
        set(v) = prefs.edit().putString("unlock_anim", v).apply()

    var layoutInitialized: Boolean
        get() = prefs.getBoolean("layout_initialized", false)
        set(v) = prefs.edit().putBoolean("layout_initialized", v).apply()

    var showLabels: Boolean
        get() = prefs.getBoolean("show_labels", true)
        set(v) = prefs.edit().putBoolean("show_labels", v).apply()

    var drawerStyle: String
        get() = prefs.getString("drawer_style", "swipe") ?: "swipe"
        set(v) = prefs.edit().putString("drawer_style", v).apply()

    var accentColor: Int
        get() = prefs.getInt("accent_color", 0xFF7C4DFF.toInt())
        set(v) = prefs.edit().putInt("accent_color", v).apply()

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
