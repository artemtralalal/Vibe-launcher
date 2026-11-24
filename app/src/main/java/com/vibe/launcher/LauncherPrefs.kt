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

    var closeArcHeight: Float
        get() = prefs.getFloat("close_arc_h", 280f)
        set(v) = prefs.edit().putFloat("close_arc_h", v).apply()

    var landscapeDockSide: String
        get() = prefs.getString("land_dock_side", "right") ?: "right"
        set(v) = prefs.edit().putString("land_dock_side", v).apply()
    var landscapeGridCols: Int
        get() = prefs.getInt("land_cols", 6)
        set(v) = prefs.edit().putInt("land_cols", v).apply()
    var landscapeIconScale: Int
        get() = prefs.getInt("land_icon_scale", 85)
        set(v) = prefs.edit().putInt("land_icon_scale", v).apply()
    var landscapeDockPadding: Int
        get() = prefs.getInt("land_dock_pad", 8)
        set(v) = prefs.edit().putInt("land_dock_pad", v).apply()
    var landscapeGridPadding: Int
        get() = prefs.getInt("land_grid_pad", 12)
        set(v) = prefs.edit().putInt("land_grid_pad", v).apply()
    var portraitGridCols: Int
        get() = prefs.getInt("port_cols", 4)
        set(v) = prefs.edit().putInt("port_cols", v).apply()
    var portraitGridPadding: Int
        get() = prefs.getInt("port_grid_pad", 16)
        set(v) = prefs.edit().putInt("port_grid_pad", v).apply()
    var gridSideMargin: Int
        get() = prefs.getInt("grid_side_margin", 16)
        set(v) = prefs.edit().putInt("grid_side_margin", v.coerceIn(0, 64)).apply()
    var iconScale: Int
        get() = prefs.getInt("icon_scale", 100)
        set(v) = prefs.edit().putInt("icon_scale", v.coerceIn(60, 140)).apply()
    var homeGridRows: Int
        get() = prefs.getInt("home_grid_rows", 6)
        set(v) = prefs.edit().putInt("home_grid_rows", v).apply()
    var openOvershoot: Float
        get() = prefs.getFloat("open_overshoot", 0.6f)
        set(v) = prefs.edit().putFloat("open_overshoot", v).apply()
    var closeOvershoot: Float
        get() = prefs.getFloat("close_overshoot", 0.4f)
        set(v) = prefs.edit().putFloat("close_overshoot", v).apply()

    var newCloseAnim: Boolean
        get() = prefs.getBoolean("new_close_anim", true)
        set(v) = prefs.edit().putBoolean("new_close_anim", v).apply()
    var closeStrength: Int
        get() = prefs.getInt("close_strength", 50)
        set(v) = prefs.edit().putInt("close_strength", v.coerceIn(0, 100)).apply()
    var closeRandomStrength: Boolean
        get() = prefs.getBoolean("close_random_strength", false)
        set(v) = prefs.edit().putBoolean("close_random_strength", v).apply()
    var closeIconScale: Int
        get() = prefs.getInt("close_icon_scale", 100)
        set(v) = prefs.edit().putInt("close_icon_scale", v.coerceIn(50, 100)).apply()

    var openZoomMode: String
        get() = prefs.getString("open_zoom_mode", "none") ?: "none" // none|in|out
        set(v) = prefs.edit().putString("open_zoom_mode", v).apply()
    var openZoomAmount: Int
        get() = prefs.getInt("open_zoom_amount", 20)
        set(v) = prefs.edit().putInt("open_zoom_amount", v.coerceIn(0, 100)).apply()
    var openZoomSpeed: Int
        get() = prefs.getInt("open_zoom_speed", 100)
        set(v) = prefs.edit().putInt("open_zoom_speed", v.coerceIn(20, 200)).apply()
    var closeZoomMode: String
        get() = prefs.getString("close_zoom_mode", "none") ?: "none"
        set(v) = prefs.edit().putString("close_zoom_mode", v).apply()
    var closeZoomAmount: Int
        get() = prefs.getInt("close_zoom_amount", 20)
        set(v) = prefs.edit().putInt("close_zoom_amount", v.coerceIn(0, 100)).apply()

    // Animation Studio
    var animStudioEnabled: Boolean
        get() = prefs.getBoolean("anim_studio_on", false)
        set(v) = prefs.edit().putBoolean("anim_studio_on", v).apply()
    var animStudioPressure: Int
        get() = prefs.getInt("anim_studio_pressure", 0)
        set(v) = prefs.edit().putInt("anim_studio_pressure", v.coerceIn(0, 200)).apply()
    var animStudioWindowPoints: String
        get() = prefs.getString("anim_studio_win_pts", "") ?: ""
        set(v) = prefs.edit().putString("anim_studio_win_pts", v).apply()
    var animStudioIconPoints: String
        get() = prefs.getString("anim_studio_icon_pts", "") ?: ""
        set(v) = prefs.edit().putString("anim_studio_icon_pts", v).apply()
    var animStudioPerspX: Int
        get() = prefs.getInt("anim_studio_px", 0)
        set(v) = prefs.edit().putInt("anim_studio_px", v.coerceIn(-100, 100)).apply()
    var animStudioPerspY: Int
        get() = prefs.getInt("anim_studio_py", 0)
        set(v) = prefs.edit().putInt("anim_studio_py", v.coerceIn(-100, 100)).apply()
    var animStudioPerspZ: Int
        get() = prefs.getInt("anim_studio_pz", 0)
        set(v) = prefs.edit().putInt("anim_studio_pz", v.coerceIn(-100, 100)).apply()
    var animStudioSpeed: Int
        get() = prefs.getInt("anim_studio_speed", 100)
        set(v) = prefs.edit().putInt("anim_studio_speed", v.coerceIn(20, 200)).apply()
    var animStudioScale: Int
        get() = prefs.getInt("anim_studio_scale", 100)
        set(v) = prefs.edit().putInt("anim_studio_scale", v.coerceIn(50, 200)).apply()
    var animStudioOffsetX: Int
        get() = prefs.getInt("anim_studio_ox", 100)
        set(v) = prefs.edit().putInt("anim_studio_ox", v.coerceIn(0, 200)).apply()
    var animStudioOffsetY: Int
        get() = prefs.getInt("anim_studio_oy", 100)
        set(v) = prefs.edit().putInt("anim_studio_oy", v.coerceIn(0, 200)).apply()
    var animStudioSyncCorners: Boolean
        get() = prefs.getBoolean("anim_studio_sync", true)
        set(v) = prefs.edit().putBoolean("anim_studio_sync", v).apply()
    var animStudioFullscreen: Boolean
        get() = prefs.getBoolean("anim_studio_full", true)
        set(v) = prefs.edit().putBoolean("anim_studio_full", v).apply()
    var animStudioWindowCorners: String
        get() = prefs.getString("anim_studio_win_c", "") ?: ""
        set(v) = prefs.edit().putString("anim_studio_win_c", v).apply()
    var animStudioIconCorners: String
        get() = prefs.getString("anim_studio_icon_c", "") ?: ""
        set(v) = prefs.edit().putString("anim_studio_icon_c", v).apply()
    var animStudioPreset: String
        get() = prefs.getString("anim_studio_preset", "close") ?: "close"
        set(v) = prefs.edit().putString("anim_studio_preset", v).apply()

    var unlockSound: Boolean
        get() = prefs.getBoolean("unlock_sound", false)
        set(v) = prefs.edit().putBoolean("unlock_sound", v).apply()
    var uiSoundVolume: Int
        get() = prefs.getInt("ui_sound_vol", 70)
        set(v) = prefs.edit().putInt("ui_sound_vol", v.coerceIn(0,100)).apply()
    var touchFxEnabled: Boolean
        get() = prefs.getBoolean("touch_fx", false)
        set(v) = prefs.edit().putBoolean("touch_fx", v).apply()
    var touchFxStyle: String
        get() = prefs.getString("touch_fx_style", "android") ?: "android"
        set(v) = prefs.edit().putString("touch_fx_style", v).apply()
    var touchFxEnd: String
        get() = prefs.getString("touch_fx_end", "shrink") ?: "shrink"
        set(v) = prefs.edit().putString("touch_fx_end", v).apply()
    var touchFxSize: Int
        get() = prefs.getInt("touch_fx_size", 50)
        set(v) = prefs.edit().putInt("touch_fx_size", v.coerceIn(10,100)).apply()
    var touchFxSpeed: Int
        get() = prefs.getInt("touch_fx_speed", 50)
        set(v) = prefs.edit().putInt("touch_fx_speed", v.coerceIn(10,100)).apply()
    var motionBlurBeta: Boolean
        get() = prefs.getBoolean("motion_blur_beta", false)
        set(v) = prefs.edit().putBoolean("motion_blur_beta", v).apply()

    var wallUnlockBlurMode: String
        get() = prefs.getString("wall_unlock_blur_mode", "from") ?: "from" // from|to
        set(v) = prefs.edit().putString("wall_unlock_blur_mode", v).apply()
    var wallUnlockBlur: Int
        get() = prefs.getInt("wall_unlock_blur", 40)
        set(v) = prefs.edit().putInt("wall_unlock_blur", v.coerceIn(0,100)).apply()
    var wallZoomMode: String
        get() = prefs.getString("wall_zoom_mode", "in") ?: "in"
        set(v) = prefs.edit().putString("wall_zoom_mode", v).apply()
    var wallZoom: Int
        get() = prefs.getInt("wall_zoom", 20)
        set(v) = prefs.edit().putInt("wall_zoom", v.coerceIn(0,100)).apply()
    var wallVideoUri: String
        get() = prefs.getString("wall_video_uri", "") ?: ""
        set(v) = prefs.edit().putString("wall_video_uri", v).apply()
    var wallLockVideoUri: String
        get() = prefs.getString("wall_lock_video_uri", "") ?: ""
        set(v) = prefs.edit().putString("wall_lock_video_uri", v).apply()

    var islandEnabled: Boolean
        get() = prefs.getBoolean("island_enabled", false)
        set(v) = prefs.edit().putBoolean("island_enabled", v).apply()
    var islandAlways: Boolean
        get() = prefs.getBoolean("island_always", false)
        set(v) = prefs.edit().putBoolean("island_always", v).apply()
    var islandRightHand: Boolean
        get() = prefs.getBoolean("island_right", false)
        set(v) = prefs.edit().putBoolean("island_right", v).apply()
    var islandX: Int
        get() = prefs.getInt("island_x", 100)
        set(v) = prefs.edit().putInt("island_x", v.coerceIn(0,200)).apply()
    var islandY: Int
        get() = prefs.getInt("island_y", 20)
        set(v) = prefs.edit().putInt("island_y", v.coerceIn(0,200)).apply()
    var islandW: Int
        get() = prefs.getInt("island_w", 100)
        set(v) = prefs.edit().putInt("island_w", v.coerceIn(20,200)).apply()
    var islandH: Int
        get() = prefs.getInt("island_h", 100)
        set(v) = prefs.edit().putInt("island_h", v.coerceIn(20,200)).apply()
    var islandPress: Int
        get() = prefs.getInt("island_press", 50)
        set(v) = prefs.edit().putInt("island_press", v.coerceIn(0,100)).apply()
    var blurEnabled: Boolean
        get() = prefs.getBoolean("blur_enabled", true)
        set(v) = prefs.edit().putBoolean("blur_enabled", v).apply()

    var liquidGlass: Boolean
        get() = prefs.getBoolean("liquid_glass", false)
        set(v) = prefs.edit().putBoolean("liquid_glass", v).apply()

    var liquidWobble: Boolean
        get() = prefs.getBoolean("liquid_wobble", true)
        set(v) = prefs.edit().putBoolean("liquid_wobble", v).apply()

    var liquidWobbleIntensity: Int
        get() = prefs.getInt("liquid_wobble_i", 50)
        set(v) = prefs.edit().putInt("liquid_wobble_i", v.coerceIn(0, 100)).apply()

    var lightMode: Boolean
        get() = prefs.getBoolean("light_mode", false)
        set(v) = prefs.edit().putBoolean("light_mode", v).apply()

    var animPreset: String
        get() = prefs.getString("anim_preset", "custom") ?: "custom" // custom | genie
        set(v) = prefs.edit().putString("anim_preset", v).apply()

    var dockStyle: String
        get() = prefs.getString("dock_style", "blur") ?: "blur" // legacy
        set(v) = prefs.edit().putString("dock_style", v).apply()

    var dockLayout: String
        get() {
            val v = prefs.getString("dock_layout", null)
            if (v != null) return if (v == "macos") "harbor" else if (v == "fold_right") "orbit" else v
            return when (dockStyle) { "macos" -> "harbor"; "fold_right" -> "orbit"; else -> "standard" }
        }
        set(v) = prefs.edit().putString("dock_layout", v).apply()

    var dockBg: String
        get() {
            val v = prefs.getString("dock_bg", null)
            if (v != null) return v
            return when (dockStyle) { "glass","solid","transparent","blur" -> dockStyle; else -> "blur" }
        }
        set(v) = prefs.edit().putString("dock_bg", v).apply()

    var dockTransparency: Int
        get() = prefs.getInt("dock_transparency", 40) // 0=opaque,100=transparent
        set(v) = prefs.edit().putInt("dock_transparency", v.coerceIn(0, 100)).apply()

    var dockRadius: Int
        get() = prefs.getInt("dock_radius", 28)
        set(v) = prefs.edit().putInt("dock_radius", v.coerceIn(0, 50)).apply()

    var dockCornerMode: String
        get() = prefs.getString("dock_corner_mode", "bottom") ?: "bottom" // bottom | bottom-left | bottom-right
        set(v) = prefs.edit().putString("dock_corner_mode", v).apply()

    private fun dockOffsetKey(axis: String): String = "dock_${axis}_offset_${dockStyle}"
    var dockXOffset: Int
        get() = prefs.getInt(dockOffsetKey("x"), prefs.getInt("dock_x_offset", 0))
        set(v) = prefs.edit().putInt(dockOffsetKey("x"), v.coerceIn(-200, 200)).apply()

    var dockYOffset: Int
        get() = prefs.getInt(dockOffsetKey("y"), prefs.getInt("dock_y_offset", 0))
        set(v) = prefs.edit().putInt(dockOffsetKey("y"), v.coerceIn(-200, 200)).apply()

    var dockHideOnOpen: Boolean
        get() = prefs.getBoolean("dock_hide_open", true)
        set(v) = prefs.edit().putBoolean("dock_hide_open", v).apply()

    var dockHideStyle: String
        get() = prefs.getString("dock_hide_style", "slide") ?: "slide" // slide | genie
        set(v) = prefs.edit().putString("dock_hide_style", v).apply()

    var dockBounceMs: Int
        get() = prefs.getInt("dock_bounce_ms", 450)
        set(v) = prefs.edit().putInt("dock_bounce_ms", v.coerceIn(0, 2000)).apply()

    var desktopMode: Boolean
        get() = prefs.getBoolean("desktop_mode", false)
        set(v) = prefs.edit().putBoolean("desktop_mode", v).apply()

    var landscapeDockPosition: String
        get() = prefs.getString("land_dock_pos", "right") ?: "right" // right | left | bottom
        set(v) = prefs.edit().putString("land_dock_pos", v).apply()

    var screenScale: Int
        get() = prefs.getInt("screen_scale", 100)
        set(v) = prefs.edit().putInt("screen_scale", v.coerceIn(50, 200)).apply()

    var refreshRatePref: Int
        get() = prefs.getInt("refresh_rate_pref", 0) // 0 auto
        set(v) = prefs.edit().putInt("refresh_rate_pref", v).apply()

    var dockScale: Int
        get() = prefs.getInt("dock_scale", 100)
        set(v) = prefs.edit().putInt("dock_scale", v.coerceIn(70, 140)).apply()

    var doubleTapLock: Boolean
        get() = prefs.getBoolean("double_tap_lock", true)
        set(v) = prefs.edit().putBoolean("double_tap_lock", v).apply()

    var lockAnimation: String
        get() = prefs.getString("lock_animation", "pixel") ?: "pixel"
        set(v) = prefs.edit().putString("lock_animation", v).apply()

    var aodEnabled: Boolean
        get() = prefs.getBoolean("aod_enabled", false)
        set(v) = prefs.edit().putBoolean("aod_enabled", v).apply()

    var fullscreenMode: Boolean
        get() = prefs.getBoolean("fullscreen_mode", false)
        set(v) = prefs.edit().putBoolean("fullscreen_mode", v).apply()

    var aodStyle: String
        get() = prefs.getString("aod_style", "hyperos") ?: "hyperos"
        set(v) = prefs.edit().putString("aod_style", v).apply()

    var aodTapToWake: String
        get() = prefs.getString("aod_tap_wake", "double") ?: "double"
        set(v) = prefs.edit().putString("aod_tap_wake", v).apply()

    var aodClockSize: Int
        get() = prefs.getInt("aod_clock_size", 56)
        set(v) = prefs.edit().putInt("aod_clock_size", v.coerceIn(24, 96)).apply()
    var aodClockX: Int
        get() = prefs.getInt("aod_clock_x", 50) // percent
        set(v) = prefs.edit().putInt("aod_clock_x", v.coerceIn(0, 100)).apply()
    var aodClockY: Int
        get() = prefs.getInt("aod_clock_y", 45)
        set(v) = prefs.edit().putInt("aod_clock_y", v.coerceIn(0, 100)).apply()
    var aodFontBold: Boolean
        get() = prefs.getBoolean("aod_font_bold", true)
        set(v) = prefs.edit().putBoolean("aod_font_bold", v).apply()
    var aodShowDate: Boolean
        get() = prefs.getBoolean("aod_show_date", true)
        set(v) = prefs.edit().putBoolean("aod_show_date", v).apply()
    var aodFpEnabled: Boolean
        get() = prefs.getBoolean("aod_fp_enabled", true)
        set(v) = prefs.edit().putBoolean("aod_fp_enabled", v).apply()
    var aodFpStyle: String
        get() = prefs.getString("aod_fp_style", "xiaomi") ?: "xiaomi"
        set(v) = prefs.edit().putString("aod_fp_style", v).apply()
    var aodFpX: Int
        get() = prefs.getInt("aod_fp_x", 50)
        set(v) = prefs.edit().putInt("aod_fp_x", v.coerceIn(0, 100)).apply()
    var aodFpY: Int
        get() = prefs.getInt("aod_fp_y", 78)
        set(v) = prefs.edit().putInt("aod_fp_y", v.coerceIn(0, 100)).apply()

    var powerSaving: Boolean
        get() = prefs.getBoolean("power_saving", false)
        set(v) = prefs.edit().putBoolean("power_saving", v).apply()

    var tiltAnimation: Boolean
        get() = prefs.getBoolean("tilt_anim", false)
        set(v) = prefs.edit().putBoolean("tilt_anim", v).apply()

    var tiltIntensity: Int
        get() = prefs.getInt("tilt_intensity", 50)
        set(v) = prefs.edit().putInt("tilt_intensity", v.coerceIn(0, 100)).apply()

    var windowCornerRadius: Int
        get() = prefs.getInt("window_corner", 32)
        set(v) = prefs.edit().putInt("window_corner", v.coerceIn(0, 60)).apply()

    var devMode: Boolean
        get() = prefs.getBoolean("dev_mode", false)
        set(v) = prefs.edit().putBoolean("dev_mode", v).apply()
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

    var drawerLayout: String
        get() = prefs.getString("drawer_layout", "grid") ?: "grid"
        set(v) = prefs.edit().putString("drawer_layout", v).apply()

    var drawerIpadMode: Boolean
        get() = prefs.getBoolean("drawer_ipad", false)
        set(v) = prefs.edit().putBoolean("drawer_ipad", v).apply()

    var musicAnimEnabled: Boolean
        get() = prefs.getBoolean("music_anim_enabled", false)
        set(v) = prefs.edit().putBoolean("music_anim_enabled", v).apply()

    var musicPermissionAsked: Boolean
        get() = prefs.getBoolean("music_perm_asked", false)
        set(v) = prefs.edit().putBoolean("music_perm_asked", v).apply()

    fun saveFolders(json: String) { prefs.edit().putString("folders_json", json).apply() }
    fun loadFolders(): String = prefs.getString("folders_json", "[]") ?: "[]"

    fun setIconOverride(key: String, uri: String) { prefs.edit().putString("icon_ovr_$key", uri).apply() }
    fun getIconOverride(key: String): String = prefs.getString("icon_ovr_$key", "") ?: ""
    fun clearIconOverride(key: String) { prefs.edit().remove("icon_ovr_$key").apply() }

    var accentColor: Int
        get() = prefs.getInt("accent_color", 0xFF7C4DFF.toInt())
        set(v) = prefs.edit().putInt("accent_color", v).apply()

    var iconRadius: Int
        get() = prefs.getInt("icon_radius", 50)
        set(v) = prefs.edit().putInt("icon_radius", v.coerceIn(0, 100)).apply()

    var iconRadiusTL: Int
        get() = prefs.getInt("icon_r_tl", -1)
        set(v) = prefs.edit().putInt("icon_r_tl", v).apply()
    var iconRadiusTR: Int
        get() = prefs.getInt("icon_r_tr", -1)
        set(v) = prefs.edit().putInt("icon_r_tr", v).apply()
    var iconRadiusBL: Int
        get() = prefs.getInt("icon_r_bl", -1)
        set(v) = prefs.edit().putInt("icon_r_bl", v).apply()
    var iconRadiusBR: Int
        get() = prefs.getInt("icon_r_br", -1)
        set(v) = prefs.edit().putInt("icon_r_br", v).apply()

    var currentPage: Int
        get() = prefs.getInt("current_page", 0)
        set(v) = prefs.edit().putInt("current_page", v).apply()

    var iconPressAnim: Boolean
        get() = prefs.getBoolean("icon_press_anim", true)
        set(v) = prefs.edit().putBoolean("icon_press_anim", v).apply()

    fun savePages(pages: List<List<String?>>, mode: String = "portrait") {
        val arr = JSONArray()
        pages.forEach { page ->
            val p = JSONArray()
            page.forEach { p.put(it ?: "") }
            arr.put(p)
        }
        prefs.edit().putString(KEY_PAGES + "_" + mode, arr.toString()).apply()
        if (mode == "portrait") prefs.edit().putString(KEY_PAGES, arr.toString()).apply()
    }

    fun loadPages(mode: String = "portrait"): List<List<String?>> {
        val raw = prefs.getString(KEY_PAGES + "_" + mode, null) ?: if (mode == "portrait") prefs.getString(KEY_PAGES, null) else null
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) { i ->
                    val p = arr.optJSONArray(i) ?: JSONArray()
                    MutableList(p.length()) { j -> p.optString(j).takeIf { it.isNotBlank() } }
                }
            } catch (_: Exception) { emptyList() }
        }
        if (mode != "portrait") return emptyList()
        val old = loadList(KEY_GRID)
        if (old.isEmpty()) return emptyList()
        val slots = MutableList<String?>(24) { null }
        old.forEachIndexed { i, k -> if (i < 24) slots[i] = k }
        return listOf(slots)
    }

    fun saveGridOrder(keys: List<String>) {
        // legacy compat: save as single page
        savePages(listOf(keys))
    }

    fun saveDockOrder(keys: List<String>, mode: String = "portrait") {
        prefs.edit().putString(KEY_DOCK + "_" + mode, JSONArray(keys).toString()).apply()
        if (mode == "portrait") prefs.edit().putString(KEY_DOCK, JSONArray(keys).toString()).apply()
    }

    fun loadGridOrder(): List<String> = loadPages().firstOrNull()?.filterNotNull() ?: emptyList()
    fun loadDockOrder(mode: String = "portrait"): List<String> {
        val raw = prefs.getString(KEY_DOCK + "_" + mode, null) ?: if (mode == "portrait") prefs.getString(KEY_DOCK, null) else null
        if (raw == null) return emptyList()
        return try { val arr = JSONArray(raw); List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
    }

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
        private const val KEY_PAGES = "home_pages"
    }
}
