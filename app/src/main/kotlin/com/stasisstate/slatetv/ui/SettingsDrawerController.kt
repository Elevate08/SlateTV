package com.stasisstate.slatetv.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.apps.AppRepository
import com.stasisstate.slatetv.billing.TipJarState
import com.stasisstate.slatetv.connector.extensions.ExtensionRegistry
import com.stasisstate.slatetv.connector.extensions.ExtensionsManagerActivity
import com.stasisstate.slatetv.connector.livetv.AppChannelsSettingsActivity
import com.stasisstate.slatetv.connector.livetv.BuiltInAppChannelsProvider
import com.stasisstate.slatetv.settings.DefaultLauncherHelper
import com.stasisstate.slatetv.settings.LauncherPreferences
import com.stasisstate.slatetv.settings.LauncherSettings
import com.stasisstate.slatetv.wallpaper.WallpaperDiskCache
import com.stasisstate.slatetv.wallpaper.plugins.PluginRegistry
import com.stasisstate.slatetv.shelf.ShelfPluginRegistry
import com.stasisstate.slatetv.shelf.launchPluginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsDrawerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val overlayView: View,
    private val itemsContainer: LinearLayout,
    private val statusSummaryView: TextView,
    private val preferences: LauncherPreferences,
    private val pluginRegistry: PluginRegistry,
    private val appRepository: AppRepository,
    private val dockPreferences: com.stasisstate.slatetv.apps.DockPreferences,
    private val diskCache: WallpaperDiskCache,
    private val onSettingsChanged: (newSettings: LauncherSettings, pluginChanged: Boolean, appsChanged: Boolean) -> Unit,
    private val shelfRegistry: ShelfPluginRegistry,
    private val extensionRegistry: ExtensionRegistry,
    private val onClearCache: () -> Unit,
    private val onTipSelected: (productId: String) -> Unit,
    private val onManageDefaultHome: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private var tipJarState: TipJarState = TipJarState()
    private var lastStatus: String = "Ready"
    private var refreshJob: Job? = null

    val isVisible: Boolean
        get() = overlayView.visibility == View.VISIBLE

    fun updateTipJarState(state: TipJarState) {
        tipJarState = state
        if (isVisible) {
            rebuild(lastStatus)
        }
    }

    fun open(lastSyncStatus: String) {
        lastStatus = lastSyncStatus
        rebuild(lastSyncStatus)
        overlayView.visibility = View.VISIBLE
        overlayView.alpha = 0f
        overlayView.animate().alpha(1f).setDuration(140L).start()
        itemsContainer.post {
            findFirstFocusable(itemsContainer)?.requestFocus()
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            withContext(Dispatchers.IO) {
                pluginRegistry.refreshExtensions()
                shelfRegistry.refresh()
            }
            if (isVisible) {
                rebuild(lastStatus)
            }
        }
    }

    fun close() {
        refreshJob?.cancel()
        refreshJob = null
        if (!isVisible) return
        overlayView.visibility = View.GONE
        onClosed()
    }

    fun rebuild(lastSyncStatus: String) {
        lastStatus = lastSyncStatus
        // Capture focused row key so toggling a setting preserves focus position cleanly
        val previouslyFocusedKey = findFocusedRowKey(itemsContainer)
        val settings = preferences.load()
        val plugins = pluginRegistry.getAllPlugins()
        val activePlugin = pluginRegistry.getPluginById(settings.activePluginId)

        val memMb = currentAppRamMb()
        val cacheMb = diskCache.totalSizeBytes() / (1024.0 * 1024.0)
        val homeStatus = DefaultLauncherHelper.getHomeStatus(context)
        val homeState = if (homeStatus.isActive) "Default Home" else "Not Default Home"
        statusSummaryView.text = "$homeState · ${memMb} MB RAM · %.1f MB cache · $lastSyncStatus".format(cacheMb)

        itemsContainer.removeAllViews()

        addSectionHeader("BACKGROUND SOURCE")
        for (plugin in plugins) {
            val isSelected = plugin.metadata.id == activePlugin.metadata.id
            val badge = if (isSelected) "Active" else plugin.metadata.description
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

        val configurableBackgrounds = plugins.filter { it.metadata.settingsActivityClassName != null }
        for (plugin in configurableBackgrounds) {
            val metadata = plugin.metadata
            val activity = metadata.settingsActivityClassName ?: continue
            val packageName = metadata.packageName ?: context.packageName
            addRow("Configure ${metadata.name}", "Open provider settings", "background-config:${metadata.id}") {
                launchPluginActivity(context, packageName, activity)
            }
        }

        addSectionHeader("HOME SHELVES (LIVE TV / GUIDES)")
        val (appChannels, otherShelves) = shelfRegistry.plugins.partition {
            it.metadata.id.startsWith(BuiltInAppChannelsProvider.PLUGIN_ID_PREFIX)
        }
        for (plugin in otherShelves) {
            val metadata = plugin.metadata
            val enabled = !shelfRegistry.isDisabled(metadata.id, settings.disabledShelfPluginIds)
            addRow(metadata.name, if (enabled) "Enabled" else "Disabled", "shelf:${metadata.id}") {
                val updated = preferences.update {
                    val disabled = shelfRegistry.migrateDisabledIds(it.disabledShelfPluginIds)
                    it.copy(disabledShelfPluginIds = if (enabled) disabled + metadata.id else disabled - metadata.id)
                }
                onSettingsChanged(updated, false, false)
                rebuild(lastSyncStatus)
            }
            metadata.settingsActivityClassName?.let { activity ->
                addRow("Configure ${metadata.name}", "Open provider settings", "shelf-config:${metadata.id}") {
                    launchPluginActivity(context, metadata.packageName, activity)
                }
            }
            metadata.guideActivityClassName?.let { activity ->
                addRow("Open Guide", metadata.name, "shelf-guide:${metadata.id}") {
                    launchPluginActivity(context, metadata.packageName, activity)
                }
            }
        }
        val appChannelsSubtitle = when (val count = appChannels.size) {
            0 -> "Choose preview channels & Watch Next from installed apps"
            1 -> "1 channel shelf active — tap to customize"
            else -> "$count channel shelves active — tap to customize"
        }
        addRow("App Channels (Installed Apps)", appChannelsSubtitle, "app-channels") {
            launchPluginActivity(context, context.packageName, AppChannelsSettingsActivity::class.java.name)
        }

        addSectionHeader("EXTENSIONS")
        val extensionCount = runCatching { extensionRegistry.installedExtensions().size }.getOrDefault(0)
        addRow(
            "Manage JSON Extensions",
            if (extensionCount > 0) {
                "$extensionCount installed — add photo feeds or shelves via QR / URL"
            } else {
                "Add photo feeds or home shelves via QR code or URL (no APKs)"
            },
            "manage-json-extensions",
        ) {
            launchPluginActivity(context, context.packageName, ExtensionsManagerActivity::class.java.name)
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

        addRow(
            "Keep Screen On (Photo Frame Mode)",
            if (settings.keepScreenOn) "On — prevents TV sleep and screensaver" else "Off — respects TV sleep timer",
        ) {
            val updated = preferences.update { it.copy(keepScreenOn = !it.keepScreenOn) }
            onSettingsChanged(updated, false, false)
            rebuild(lastSyncStatus)
        }

        addSectionHeader("APPS & SYSTEM")

        addRow(
            "Default Home Launcher",
            when {
                homeStatus.isActive -> "Active"
                homeStatus.isOverridden -> "Home still opens another launcher. View setup instructions."
                else -> "Set up Slate TV as your Home launcher"
            },
            "default-home",
            onClick = onManageDefaultHome,
        )

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
            "Open device preferences",
        ) {
            close()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        addSectionHeader("SUPPORT SLATE TV")
        addTipJarSection()

        if (previouslyFocusedKey != null) {
            itemsContainer.post {
                findRowByKey(itemsContainer, previouslyFocusedKey)?.requestFocus()
                    ?: findFirstFocusable(itemsContainer)?.requestFocus()
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

    private fun addRow(title: String, subtitle: String, rowKey: String = title, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            tag = rowKey
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

    private fun addTipJarSection() {
        itemsContainer.addView(TextView(context).apply {
            text = "Slate TV is completely free and unrestricted. Optional tips support development and unlock nothing."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })

        if (tipJarState.available && tipJarState.products.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            for ((index, product) in tipJarState.products.withIndex()) {
                val btn = TextView(context).apply {
                    tag = "tip:${product.productId}"
                    text = product.formattedPrice
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        if (index > 0) marginStart = dp(6)
                    }
                    setOnClickListener { onTipSelected(product.productId) }
                }
                row.addView(btn)
            }
            itemsContainer.addView(row)
        } else {
            itemsContainer.addView(TextView(context).apply {
                text = "Optional tips ($1, $3, $5, $10, $20) are available when Slate TV is installed from Google Play."
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(dp(4), dp(2), dp(4), dp(10))
            })
        }
    }

    private fun findFocusedRowKey(group: ViewGroup): String? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.hasFocus()) {
                if (child.tag is String) return child.tag as String
                if (child is ViewGroup) {
                    findFocusedRowKey(child)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findRowByKey(group: ViewGroup, key: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.tag == key && child.isFocusable) return child
            if (child is ViewGroup) {
                findRowByKey(child, key)?.let { return it }
            }
        }
        return null
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
