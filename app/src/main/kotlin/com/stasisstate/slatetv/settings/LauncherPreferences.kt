package com.stasisstate.slatetv.settings

import android.content.Context
import android.content.SharedPreferences
import com.stasisstate.slatetv.wallpaper.plugins.AmbientSlatePlugin

data class LauncherSettings(
    val activePluginId: String = AmbientSlatePlugin.PLUGIN_ID,
    val rotationIntervalSeconds: Int = 300, // 5 minutes
    val idleGallerySeconds: Int = 45,       // 45 seconds (0 = disabled)
    val showClock: Boolean = true,
    val showCaption: Boolean = true,
    val backdropDimPercent: Int = 18,       // 0..60% subtle dim over photos
    val smartPortraitBlur: Boolean = true,
    val keepScreenOn: Boolean = false,      // Opt-in for dedicated digital photo frame mode (respects OLED/Shield sleep by default)
    val disabledShelfPluginIds: Set<String> = emptySet(),
    val showSideloadedApps: Boolean = false,
) {
    val rotationIntervalLabel: String
        get() = when (rotationIntervalSeconds) {
            0 -> "Manual only"
            60 -> "1 minute"
            180 -> "3 minutes"
            300 -> "5 minutes"
            600 -> "10 minutes"
            1800 -> "30 minutes"
            else -> "${rotationIntervalSeconds}s"
        }

    val idleGalleryLabel: String
        get() = when (idleGallerySeconds) {
            0 -> "Off"
            20 -> "20 seconds"
            45 -> "45 seconds"
            120 -> "2 minutes"
            else -> "${idleGallerySeconds}s"
        }
}

class LauncherPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LauncherSettings = LauncherSettings(
        activePluginId = prefs.getString(KEY_ACTIVE_PLUGIN, AmbientSlatePlugin.PLUGIN_ID)
            ?: AmbientSlatePlugin.PLUGIN_ID,
        rotationIntervalSeconds = prefs.getInt(KEY_ROTATION_SECONDS, 300),
        idleGallerySeconds = prefs.getInt(KEY_IDLE_GALLERY_SECONDS, 45),
        showClock = prefs.getBoolean(KEY_SHOW_CLOCK, true),
        showCaption = prefs.getBoolean(KEY_SHOW_CAPTION, true),
        backdropDimPercent = prefs.getInt(KEY_BACKDROP_DIM, 18).coerceIn(0, 60),
        smartPortraitBlur = prefs.getBoolean(KEY_SMART_PORTRAIT, true),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        disabledShelfPluginIds = prefs.getStringSet("disabled_shelf_plugin_ids", emptySet()).orEmpty().toSet(),
        showSideloadedApps = prefs.getBoolean(KEY_SHOW_SIDELOADED, false),
    )

    fun update(transform: (LauncherSettings) -> LauncherSettings): LauncherSettings {
        val next = transform(load())
        save(next)
        return next
    }

    fun save(settings: LauncherSettings) {
        prefs.edit()
            .putString(KEY_ACTIVE_PLUGIN, settings.activePluginId)
            .putInt(KEY_ROTATION_SECONDS, settings.rotationIntervalSeconds)
            .putInt(KEY_IDLE_GALLERY_SECONDS, settings.idleGallerySeconds)
            .putBoolean(KEY_SHOW_CLOCK, settings.showClock)
            .putBoolean(KEY_SHOW_CAPTION, settings.showCaption)
            .putInt(KEY_BACKDROP_DIM, settings.backdropDimPercent.coerceIn(0, 60))
            .putBoolean(KEY_SMART_PORTRAIT, settings.smartPortraitBlur)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putStringSet("disabled_shelf_plugin_ids", settings.disabledShelfPluginIds.toSet())
            .putBoolean(KEY_SHOW_SIDELOADED, settings.showSideloadedApps)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "slate_launcher_settings"
        private const val KEY_ACTIVE_PLUGIN = "active_plugin_id"
        private const val KEY_ROTATION_SECONDS = "rotation_seconds"
        private const val KEY_IDLE_GALLERY_SECONDS = "idle_gallery_seconds"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_CAPTION = "show_caption"
        private const val KEY_BACKDROP_DIM = "backdrop_dim_percent"
        private const val KEY_SMART_PORTRAIT = "smart_portrait_blur"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SHOW_SIDELOADED = "show_sideloaded_apps"

        val ROTATION_OPTIONS = listOf(0, 60, 180, 300, 600, 1800)
        val IDLE_OPTIONS = listOf(0, 20, 45, 120)
        val DIM_OPTIONS = listOf(0, 10, 18, 30, 45)
    }
}
