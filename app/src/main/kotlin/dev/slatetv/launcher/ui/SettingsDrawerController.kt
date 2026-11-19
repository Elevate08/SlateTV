package dev.slatetv.launcher.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.slatetv.launcher.R
import dev.slatetv.launcher.apps.AppRepository
import dev.slatetv.launcher.settings.LauncherPreferences
import dev.slatetv.launcher.settings.LauncherSettings
import dev.slatetv.launcher.wallpaper.WallpaperDiskCache
import dev.slatetv.launcher.wallpaper.plugins.PluginRegistry
import dev.slatetv.plugin.immich.ImmichBackgroundPlugin

class SettingsDrawerController(
    private val context: Context,
    private val overlayView: View,
    private val itemsContainer: LinearLayout,
    private val statusSummaryView: TextView,
    private val preferences: LauncherPreferences,
    private val pluginRegistry: PluginRegistry,
    private val appRepository: AppRepository,
    private val dockPreferences: dev.slatetv.launcher.apps.DockPreferences,
    private val diskCache: WallpaperDiskCache,
    private val onSettingsChanged: (newSettings: LauncherSettings, pluginChanged: Boolean, appsChanged: Boolean) -> Unit,
    private val onOpenImmichSetup: () -> Unit,
    private val onClearCache: () -> Unit,
    private val onClosed: () -> Unit,
) {
    val isVisible: Boolean
        get() = overlayView.visibility == View.VISIBLE

    fun open(lastSyncStatus: String) {
        pluginRegistry.refreshExternalPlugins()
        rebuild(lastSyncStatus)
        overlayView.visibility = View.VISIBLE
        overlayView.alpha = 0f
        overlayView.animate().alpha(1f).setDuration(140L).start()
        itemsContainer.post {
            findFirstFocusable(itemsContainer)?.requestFocus()
        }
    }

    fun close() {
        if (!isVisible) return
        overlayView.visibility = View.GONE
        onClosed()
    }

    fun rebuild(lastSyncStatus: String) {
        val settings = preferences.load()
        val plugins = pluginRegistry.getAllPlugins()
        val activePlugin = pluginRegistry.getPluginById(settings.activePluginId)

        val memMb = currentAppRamMb()
        val cacheMb = diskCache.totalSizeBytes() / (1024.0 * 1024.0)
        val homeState = if (appRepository.isSlateDefaultHome()) "Default Home" else "Not Default Home"
        statusSummaryView.text = "$homeState · ${memMb} MB RAM · %.1f MB cache · $lastSyncStatus".format(cacheMb)

        itemsContainer.removeAllViews()

        addSectionHeader("BACKGROUND PLUGIN")
        for (plugin in plugins) {
            val isSelected = plugin.metadata.id == activePlugin.metadata.id
            val badge = when {
                isSelected && plugin.metadata.isExternal -> "Active · External APK"
                isSelected -> "Active"
                plugin.metadata.isExternal -> "External APK plugin"
                else -> plugin.metadata.description
            }
            addRow(
                title = (if (isSelected) "●  " else "○  ") + plugin.metadata.name,
                subtitle = badge,
            ) {
                if (plugin.metadata.id != settings.activePluginId) {
                    val updated = preferences.update { it.copy(activePluginId = plugin.metadata.id) }
                    onSettingsChanged(updated, true, false)
                    rebuild("Switching to ${plugin.metadata.name}…")
                }
            }
        }

        // Plugin Configuration Shortcut
        if (activePlugin.metadata.id == ImmichBackgroundPlugin.PLUGIN_ID) {
            addRow(
                title = "Configure Immich Plugin…",
                subtitle = "Server URL, QR phone pairing, albums, quality",
            ) {
                onOpenImmichSetup()
            }
        } else if (activePlugin.metadata.isExternal &&
            !activePlugin.metadata.packageName.isNullOrBlank() &&
            !activePlugin.metadata.settingsActivityClassName.isNullOrBlank()
        ) {
            addRow(
                title = "Configure ${activePlugin.metadata.name}…",
                subtitle = "Open external plugin settings",
            ) {
                val intent = Intent().apply {
                    component = ComponentName(
                        activePlugin.metadata.packageName!!,
                        activePlugin.metadata.settingsActivityClassName!!,
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }.onFailure {
                    Toast.makeText(context, "Unable to open plugin settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        addSectionHeader("DISPLAY & GALLERY")

        addRow("Photo Rotation Interval", settings.rotationIntervalLabel) {
            val opts = LauncherPreferences.ROTATION_OPTIONS
            val next = opts[(opts.indexOf(settings.rotationIntervalSeconds) + 1) % opts.size]
            val updated = preferences.update { it.copy(rotationIntervalSeconds = next) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addRow("Idle Gallery Mode (Hide Dock)", settings.idleGalleryLabel) {
            val opts = LauncherPreferences.IDLE_OPTIONS
            val next = opts[(opts.indexOf(settings.idleGallerySeconds) + 1) % opts.size]
            val updated = preferences.update { it.copy(idleGallerySeconds = next) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addRow("Backdrop Dimming", "${settings.backdropDimPercent}%") {
            val opts = LauncherPreferences.DIM_OPTIONS
            val next = opts[(opts.indexOf(settings.backdropDimPercent) + 1) % opts.size]
            val updated = preferences.update { it.copy(backdropDimPercent = next) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addRow("Show Clock", if (settings.showClock) "On" else "Off") {
            val updated = preferences.update { it.copy(showClock = !it.showClock) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addRow("Show Photo Date & Location", if (settings.showCaption) "On" else "Off") {
            val updated = preferences.update { it.copy(showCaption = !it.showCaption) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addRow("Smart Portrait Blur Pillarbox", if (settings.smartPortraitBlur) "On" else "Off") {
            val updated = preferences.update { it.copy(smartPortraitBlur = !it.smartPortraitBlur) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addSectionHeader("APPS & SHIELD SYSTEM")

        addRow(
            "Include Sideloaded Phone Apps",
            if (settings.showSideloadedApps) "Showing TV + sideloaded apps" else "TV apps only",
        ) {
            val updated = preferences.update { it.copy(showSideloadedApps = !it.showSideloadedApps) }
            onSettingsChanged(updated, false, true)
            rebuild(lastSyncStatus)
        }

        val hiddenCount = dockPreferences.getHiddenPackages().size
        if (hiddenCount > 0) {
            addRow(
                "Restore Hidden Apps",
                "$hiddenCount app(s) hidden — tap to unhide all",
            ) {
                dockPreferences.setHiddenPackages(emptySet())
                onSettingsChanged(settings, false, true)
                rebuild("Restored $hiddenCount hidden app(s)")
            }
        }

        addRow("Clear Photo Disk Cache", "%.1f MB used".format(cacheMb)) {
            onClearCache()
            rebuild("Cache cleared")
        }

        addRow(
            "Android TV System Settings",
            "Open Shield Device Preferences / Default Home",
        ) {
            close()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun addSectionHeader(label: String) {
        itemsContainer.addView(TextView(context).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.09f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(4), dp(14), dp(4), dp(6))
        })
    }

    private fun addRow(title: String, subtitle: String, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) }
            setOnClickListener { onClick() }
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        row.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        itemsContainer.addView(row)
    }

    private fun findFirstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isFocusable) return child
        }
        return null
    }

    private fun currentAppRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
        return (info.firstOrNull()?.totalPss ?: 0) / 1024L
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
