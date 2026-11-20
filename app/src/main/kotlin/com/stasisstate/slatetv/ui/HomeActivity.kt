package com.stasisstate.slatetv.ui

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.SlateApplication
import com.stasisstate.slatetv.apps.AppInfo
import com.stasisstate.slatetv.billing.TipJar
import com.stasisstate.slatetv.calendar.CalendarAgenda
import com.stasisstate.slatetv.calendar.GoogleCalendarSource
import com.stasisstate.slatetv.connector.homeassistant.HaStatus
import com.stasisstate.slatetv.connector.homeassistant.HomeAssistantRuntime
import com.stasisstate.slatetv.connector.homeassistant.HomeAssistantSettingsActivity
import com.stasisstate.slatetv.settings.DefaultLauncherHelper
import com.stasisstate.slatetv.settings.LauncherSettings
import androidx.lifecycle.lifecycleScope
import com.stasisstate.slatetv.shelf.ShelfManager
import com.stasisstate.slatetv.shelf.ShelfPluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Slate TV Home Activity.
 *
 * Engineered specifically to eliminate black screens and wake-up lag on Android TV:
 * - Zero Accessibility Service required.
 * - Handles HDMI-CEC display configuration changes in-place without Activity recreation.
 * - Hydrates cached backdrop immediately (< 20ms) with guaranteed non-black gradient underneath.
 * - Automatically restores D-pad focus and retries network sync on wake-up.
 */
class HomeActivity : AppCompatActivity() {

    private val app: SlateApplication
        get() = application as SlateApplication

    private lateinit var backdropRenderer: BackdropRenderer
    private lateinit var hudContainer: ViewGroup
    private lateinit var photoCaptionText: TextView
    private lateinit var offlineBadge: TextView
    private lateinit var homeClock: TextClock
    private lateinit var focusedLabelText: TextView
    private lateinit var dockRecyclerView: RecyclerView
    private lateinit var btnAllApps: ImageButton
    private lateinit var btnNextPhoto: ImageButton
    private lateinit var btnSettings: ImageButton

    private lateinit var calendarPanel: HomeCalendarPanel
    private val calendarSource by lazy { GoogleCalendarSource(applicationContext) }
    private var calendarAgenda: CalendarAgenda? = null
    private var calendarRefreshJob: Job? = null
    private var calendarInForeground = false

    private val shelfRegistry by lazy { ShelfPluginRegistry(this, app.extensionRegistry) }
    private val shelfManager by lazy {
        ShelfManager(lifecycleScope, shelfRegistry) { migratedDisabledShelfIds() }
    }

    /**
     * Returns saved disabled shelf IDs with unambiguous legacy manifest IDs translated to canonical
     * IDs, persisting the migration once the registry has resolved them.
     */
    private fun migratedDisabledShelfIds(): Set<String> {
        val saved = app.launcherPreferences.load().disabledShelfPluginIds
        val migrated = shelfRegistry.migrateDisabledIds(saved)
        if (migrated != saved) {
            app.launcherPreferences.update { it.copy(disabledShelfPluginIds = shelfRegistry.migrateDisabledIds(it.disabledShelfPluginIds)) }
        }
        return migrated
    }
    private lateinit var shelfController: HomeShelfController
    private lateinit var haControls: HomeAssistantControlsPanel
    private var lastDockFocus: View? = null
    private lateinit var dockAdapter: AppDockAdapter
    private lateinit var allAppsController: AllAppsOverlayController
    private lateinit var settingsController: SettingsDrawerController
    private lateinit var tipJar: TipJar

    private var allInstalledApps: List<AppInfo> = emptyList()
    private var dockApps: List<AppInfo> = emptyList()
    private var currentSettings: LauncherSettings = LauncherSettings()
    private var isIdleGalleryMode = false
    private var isContextMenuOpen = false
    private var reorderingPackageName: String? = null
    private var reorderingLabel: String? = null
    private val dockReorder = DockReorderController()
    private var skipNextResumeShelfRefresh = false
    private var wallpaperCollectJob: Job? = null
    private var tipJarCollectJob: Job? = null
    private var reloadAppsJob: Job? = null

    private val homePickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onHomePickerResult() }

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setShowCalendarEvents(true)
            } else {
                Toast.makeText(this, R.string.calendar_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val enterIdleRunnable = Runnable { enterIdleGalleryMode() }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lifecycleScope.launch {
                reloadApps()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                offlineBadge.visibility = View.GONE
                app.wallpaperManager.onNetworkRestored()
                shelfManager.refresh()
                HomeAssistantRuntime.reconnectIfIdle()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Fires once DHCP/LAN routing finishes after TV wake-up
            runOnUiThread {
                offlineBadge.visibility = View.GONE
                app.wallpaperManager.onNetworkRestored()
                shelfManager.refresh()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                updateOfflineIndicator()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            // HDMI-CEC TV power-on / HDR switch recovery
            backdropRenderer.ensureVisibleAfterWake()
            ensureValidFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        bindViews()
        setupTipJar()
        setupDock()
        shelfController = HomeShelfController(findViewById(R.id.homeShelfContainer), lifecycleScope, app.sharedHttpClient)
        lifecycleScope.launch {
            shelfManager.shelves.collect { shelves ->
                val hadFocus = shelfController.hasFocus()
                shelfController.render(shelves)
                if (hadFocus && !shelfController.hasFocus()) restoreDockFocus()
            }
        }
        setupHomeAssistantControls()
        setupActionPills()
        setupOverlays()
        registerBackHandler()

        currentSettings = app.launcherPreferences.load()
        applyUiPreferences(currentSettings)

        val metrics = resources.displayMetrics
        app.wallpaperManager.configureDisplay(metrics.widthPixels, metrics.heightPixels)
        app.wallpaperManager.start(currentSettings)

        observeWallpaperState()
        reloadApps()
        registerSystemObservers()
        resetIdleTimer()
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.registerDisplayListener(displayListener, mainHandler)
        }
        shelfManager.start()
        skipNextResumeShelfRefresh = true
    }

    override fun onStop() {
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(displayListener)
        }
        shelfManager.stop()
        skipNextResumeShelfRefresh = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        calendarInForeground = true
        refreshCalendarEvents()
        exitIdleGalleryMode()
        backdropRenderer.ensureVisibleAfterWake()
        app.wallpaperManager.onResume()
        HomeAssistantRuntime.syncListener()
        val shelfRefreshStarted = skipNextResumeShelfRefresh
        skipNextResumeShelfRefresh = false
        if (!shelfRefreshStarted || settingsController.isVisible) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    app.pluginRegistry.refreshExtensions()
                    shelfRegistry.refresh()
                }
                if (!shelfRefreshStarted) shelfManager.refresh()
                refreshSettingsIfOpen()
            }
        }
        updateOfflineIndicator()
        ensureValidFocus()
        resetIdleTimer()
        refreshSettingsIfOpen()
    }

    override fun onPause() {
        calendarInForeground = false
        refreshCalendarEvents()
        exitDockReorderMode()
        super.onPause()
        mainHandler.removeCallbacks(enterIdleRunnable)
        app.wallpaperManager.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            backdropRenderer.ensureVisibleAfterWake()
            ensureValidFocus()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshCalendarEvents()
        val metrics = resources.displayMetrics
        app.wallpaperManager.configureDisplay(metrics.widthPixels, metrics.heightPixels)
        backdropRenderer.ensureVisibleAfterWake()
        ensureValidFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home closes any open overlay and restores the calm home screen
        exitDockReorderMode()
        if (allAppsController.isVisible) allAppsController.close()
        if (settingsController.isVisible) settingsController.close()
        exitIdleGalleryMode()
        ensureValidFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        wallpaperCollectJob?.cancel()
        tipJarCollectJob?.cancel()
        reloadAppsJob?.cancel()
        mainHandler.removeCallbacks(enterIdleRunnable)
        backdropRenderer.releaseDrawables()
        tipJar.release()
        unregisterSystemObservers()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleDockReorderKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isIdleGalleryMode) {
                exitIdleGalleryMode()
                resetIdleTimer()
                // Consume wake-up key except Media Next
                if (event.keyCode != KeyEvent.KEYCODE_MEDIA_NEXT) {
                    return true
                }
            } else {
                resetIdleTimer()
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_CHANNEL_UP,
                -> {
                    app.wallpaperManager.requestNext(forceFresh = true)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.repeatCount == 0 && !allAppsController.isVisible && !settingsController.isVisible) {
                        if (haControls.hasFocus()) {
                            if (shelfController.requestFocus()) return true
                        } else if (!shelfController.hasFocus()) {
                            lastDockFocus = currentFocus
                            if (haControls.requestFocus()) return true
                            if (shelfController.requestFocus()) return true
                        } else if (shelfController.moveVertical(KeyEvent.KEYCODE_DPAD_UP)) {
                            return true
                        }
                        enterIdleGalleryMode()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (shelfController.hasFocus() && shelfController.moveHorizontal(event.keyCode)) return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (shelfController.hasFocus() && !allAppsController.isVisible && !settingsController.isVisible) {
                        if (!shelfController.moveVertical(KeyEvent.KEYCODE_DPAD_DOWN) && !haControls.requestFocus()) {
                            restoreDockFocus()
                        }
                        return true
                    }
                    if (haControls.hasFocus() && !allAppsController.isVisible && !settingsController.isVisible) {
                        restoreDockFocus()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            },
        )
    }

    private fun handleBackNavigation() {
        when {
            reorderingPackageName != null -> exitDockReorderMode()
            settingsController.isVisible -> settingsController.close()
            allAppsController.isVisible -> allAppsController.close()
            isIdleGalleryMode -> {
                exitIdleGalleryMode()
                resetIdleTimer()
            }
            else -> enterIdleGalleryMode()
        }
    }

    private fun bindViews() {
        val layerA = findViewById<ImageView>(R.id.backdropLayerA)
        val layerB = findViewById<ImageView>(R.id.backdropLayerB)
        val portraitForeground = findViewById<ImageView>(R.id.portraitForeground)
        val dimView = findViewById<View>(R.id.backdropDimView)
        backdropRenderer = BackdropRenderer(layerA, layerB, portraitForeground, dimView)

        hudContainer = findViewById(R.id.hudContainer)
        photoCaptionText = findViewById(R.id.photoCaptionText)
        offlineBadge = findViewById(R.id.offlineBadge)
        homeClock = findViewById(R.id.homeClock)
        calendarPanel = HomeCalendarPanel(findViewById(R.id.homeCalendarPanel))
        focusedLabelText = findViewById(R.id.focusedLabelText)
        dockRecyclerView = findViewById(R.id.dockRecyclerView)
        btnAllApps = findViewById(R.id.btnAllApps)
        btnNextPhoto = findViewById(R.id.btnNextPhoto)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun setupDock() {
        dockAdapter = AppDockAdapter(
            onAppClick = { info ->
                if (reorderingPackageName != null) exitDockReorderMode() else launchApp(info)
            },
            onAppLongClick = { info ->
                if (reorderingPackageName == null) showAppContextMenu(info, fromDock = true)
            },
            onAppFocused = { info ->
                val reordering = reorderingPackageName
                focusedLabelText.text = if (reordering != null) {
                    DockReorderController.prompt(reorderingLabel ?: info.label)
                } else {
                    info.label
                }
            },
        )
        dockRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockRecyclerView.adapter = dockAdapter
        dockRecyclerView.itemAnimator = null
    }

    private fun setupActionPills() {
        configurePill(btnAllApps, "All Apps") {
            shelfController.clearPendingFocus()
            hudContainer.visibility = View.INVISIBLE
            allAppsController.open()
        }
        configurePill(btnNextPhoto, "Next Photo") {
            app.wallpaperManager.requestNext(forceFresh = true)
        }
        configurePill(btnSettings, "Settings") {
            shelfController.clearPendingFocus()
            hudContainer.visibility = View.INVISIBLE
            tipJar.connect()
            settingsController.open(app.wallpaperManager.state.value.lastSyncStatus)
        }
    }

    private fun configurePill(button: ImageButton, label: String, onClick: () -> Unit) {
        button.setColorFilter(Color.WHITE)
        button.setOnClickListener { onClick() }
        button.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.12f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
            button.setColorFilter(if (hasFocus) Color.parseColor("#0A0D12") else Color.WHITE)
            if (hasFocus) {
                focusedLabelText.text = label
            }
        }
    }

    private fun setupOverlays() {
        allAppsController = AllAppsOverlayController(
            overlayView = findViewById(R.id.allAppsOverlay),
            recyclerView = findViewById(R.id.allAppsRecyclerView),
            onLaunchApp = { launchApp(it) },
            onAppContextAction = { showAppContextMenu(it, fromDock = false) },
            onClosed = {
                hudContainer.visibility = View.VISIBLE
                ensureValidFocus()
            },
        )

        settingsController = SettingsDrawerController(
            context = this,
            scope = lifecycleScope,
            overlayView = findViewById(R.id.settingsOverlay),
            itemsContainer = findViewById(R.id.settingsItemsContainer),
            statusSummaryView = findViewById(R.id.settingsStatusSummary),
            preferences = app.launcherPreferences,
            pluginRegistry = app.pluginRegistry,
            shelfRegistry = shelfRegistry,
            extensionRegistry = app.extensionRegistry,
            appRepository = app.appRepository,
            dockPreferences = app.dockPreferences,
            diskCache = app.diskCache,
            onSettingsChanged = { updated, pluginChanged, appsChanged ->
                if (currentSettings.disabledShelfPluginIds != updated.disabledShelfPluginIds) {
                    shelfManager.refresh()
                }
                currentSettings = updated
                applyUiPreferences(updated)
                app.wallpaperManager.applySettings(updated, pluginChanged)
                if (appsChanged) reloadApps()
            },
            onClearCache = {
                app.diskCache.clear()
                Toast.makeText(this, "Photo cache cleared", Toast.LENGTH_SHORT).show()
            },
            onTipSelected = { productId ->
                tipJar.launch(this, productId)
            },
            onManageDefaultHome = { showDefaultLauncherDialog() },
            calendarStatus = ::calendarSettingsStatus,
            onCalendarSettingClicked = ::onCalendarSettingClicked,
            homeAssistantStatus = ::homeAssistantSettingsStatus,
            onHomeAssistantClicked = {
                runCatching {
                    startActivity(Intent(this, HomeAssistantSettingsActivity::class.java))
                }
            },
            onClosed = {
                hudContainer.visibility = View.VISIBLE
                ensureValidFocus()
            },
        )
    }

    private fun setupHomeAssistantControls() {
        haControls = HomeAssistantControlsPanel(
            container = findViewById(R.id.homeAssistantControls),
            scope = lifecycleScope,
            onFocusLabel = { focusedLabelText.text = it },
            onDialogOpen = { open ->
                isContextMenuOpen = open
                if (open) mainHandler.removeCallbacks(enterIdleRunnable) else resetIdleTimer()
            },
        )
        lifecycleScope.launch {
            HomeAssistantRuntime.config.combine(HomeAssistantRuntime.entities) { config, entities -> config to entities }
                .collect { (config, entities) ->
                    val hadFocus = haControls.hasFocus()
                    haControls.render(config, entities)
                    if (hadFocus && !haControls.hasFocus()) restoreDockFocus()
                }
        }
        lifecycleScope.launch {
            HomeAssistantRuntime.status.collect {
                if (::settingsController.isInitialized) refreshSettingsIfOpen()
            }
        }
    }

    private fun homeAssistantSettingsStatus(): String {
        val config = HomeAssistantRuntime.config.value
        return when (HomeAssistantRuntime.status.value) {
            HaStatus.NotConfigured -> getString(R.string.ha_settings_not_configured)
            HaStatus.Connecting -> getString(R.string.ha_settings_connecting)
            HaStatus.Connected -> getString(
                R.string.ha_settings_connected,
                resources.getQuantityString(R.plurals.ha_settings_controls, config.homeEntityIds.size, config.homeEntityIds.size),
                resources.getQuantityString(R.plurals.ha_settings_doorbells, config.doorbells.size, config.doorbells.size),
            )
            HaStatus.AuthFailed -> getString(R.string.ha_settings_auth_failed)
            is HaStatus.Disconnected -> getString(R.string.ha_settings_offline)
        }
    }

    private fun calendarSettingsStatus(): Int = when {
        !calendarSource.isProviderAvailable() -> R.string.calendar_setting_unsupported
        !currentSettings.showCalendarEvents -> R.string.calendar_setting_off
        !calendarSource.hasReadPermission() -> R.string.calendar_setting_permission_required
        else -> when (val agenda = calendarAgenda) {
            null -> R.string.calendar_setting_on
            CalendarAgenda.PermissionRequired -> R.string.calendar_setting_permission_required
            CalendarAgenda.Unavailable -> R.string.calendar_setting_unavailable
            CalendarAgenda.NoSyncedCalendars -> R.string.calendar_setting_not_synced
            CalendarAgenda.MultipleAccounts -> R.string.calendar_setting_multiple_accounts
            is CalendarAgenda.Ready -> if (agenda.events.isEmpty()) {
                R.string.calendar_setting_no_events
            } else {
                R.string.calendar_setting_on
            }
        }
    }

    private fun onCalendarSettingClicked() {
        when {
            currentSettings.showCalendarEvents -> setShowCalendarEvents(false)
            !calendarSource.isProviderAvailable() -> {
                Toast.makeText(this, R.string.calendar_provider_missing, Toast.LENGTH_SHORT).show()
            }
            calendarSource.hasReadPermission() -> setShowCalendarEvents(true)
            else -> {
                runCatching { calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR) }
                    .onFailure {
                        Toast.makeText(this, R.string.calendar_permission_prompt_unavailable, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun setShowCalendarEvents(show: Boolean) {
        currentSettings = app.launcherPreferences.update { it.copy(showCalendarEvents = show) }
        refreshCalendarEvents()
        refreshSettingsIfOpen()
    }

    private fun refreshCalendarEvents() {
        calendarRefreshJob?.cancel()
        calendarRefreshJob = null
        if (!calendarInForeground || !currentSettings.showCalendarEvents) {
            showCalendarAgenda(null)
            return
        }
        calendarRefreshJob = lifecycleScope.launch {
            while (isActive) {
                val agenda = calendarSource.readUpcoming()
                ensureActive()
                showCalendarAgenda(
                    if (agenda is CalendarAgenda.Ready && !calendarSource.hasReadPermission()) {
                        CalendarAgenda.PermissionRequired
                    } else {
                        agenda
                    },
                )
                delay(60_000L)
            }
        }
    }

    private fun showCalendarAgenda(agenda: CalendarAgenda?) {
        val previous = calendarAgenda
        calendarAgenda = agenda
        val events = (agenda as? CalendarAgenda.Ready)?.events.orEmpty()
        calendarPanel.render(events)
        val statusChanged = previous?.javaClass != agenda?.javaClass ||
            (previous is CalendarAgenda.Ready && previous.events.isEmpty() != events.isEmpty())
        if (agenda != null && statusChanged) {
            refreshSettingsIfOpen()
        }
    }

    private fun launchHomePicker(intents: List<Intent>): Boolean =
        intents.any { runCatching { homePickerLauncher.launch(it) }.isSuccess }

    private fun onHomePickerResult() {
        refreshSettingsIfOpen()
        if (DefaultLauncherHelper.getHomeStatus(this).isActive) {
            Toast.makeText(this, "Slate TV is your Default Home launcher", Toast.LENGTH_SHORT).show()
        } else {
            showDefaultLauncherDialog()
        }
    }

    private fun refreshSettingsIfOpen() {
        if (settingsController.isVisible) settingsController.rebuild(app.wallpaperManager.state.value.lastSyncStatus)
    }

    private fun showDefaultLauncherDialog() {
        DefaultLauncherHelper.showDefaultLauncherDialog(
            activity = this,
            onSetDefault = {
                val roleIntent = DefaultLauncherHelper.buildRequestHomeRoleIntent(this)
                launchHomePicker(listOfNotNull(roleIntent) + DefaultLauncherHelper.buildHomeSettingsFallbackIntents())
            },
            onOpenHomeSettings = { launchHomePicker(DefaultLauncherHelper.buildHomeSettingsFallbackIntents()) },
        )
    }

    private fun setupTipJar() {
        tipJar = TipJar(this) { message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
        tipJarCollectJob?.cancel()
        tipJarCollectJob = lifecycleScope.launch {
            tipJar.state.collect { state ->
                if (::settingsController.isInitialized) {
                    settingsController.updateTipJarState(state)
                }
            }
        }
    }

    private fun observeWallpaperState() {
        wallpaperCollectJob?.cancel()
        wallpaperCollectJob = lifecycleScope.launch {
            app.wallpaperManager.state.collect { uiState ->
                uiState.backdrop?.let { backdrop ->
                    backdropRenderer.render(backdrop, animate = true)
                    val subtitle = backdrop.subtitle
                    if (currentSettings.showCaption && !subtitle.isNullOrBlank()) {
                        photoCaptionText.text = subtitle
                        photoCaptionText.visibility = View.VISIBLE
                    } else {
                        photoCaptionText.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun applyUiPreferences(settings: LauncherSettings) {
        backdropRenderer.setDimPercent(settings.backdropDimPercent)
        homeClock.visibility = if (settings.showClock) View.VISIBLE else View.GONE
        val currentSubtitle = app.wallpaperManager.state.value.backdrop?.subtitle
        photoCaptionText.visibility =
            if (settings.showCaption && !currentSubtitle.isNullOrBlank()) View.VISIBLE else View.GONE
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        resetIdleTimer()
    }

    private fun reloadApps() {
        reloadAppsJob?.cancel()
        reloadAppsJob = lifecycleScope.launch {
            val loaded = app.appRepository.loadInstalledApps(currentSettings.showSideloadedApps)
            if (isDestroyed || isFinishing) return@launch
            allInstalledApps = loaded
            app.dockPreferences.seedDefaultsIfNeeded(loaded.map { it.packageName })

            val hidden = app.dockPreferences.getHiddenPackages()
            val visibleApps = loaded.filterNot { it.packageName in hidden }
            val byPkg = visibleApps.associateBy { it.packageName }

            val pinnedOrder = app.dockPreferences.getPinnedPackages()
            val loadedDock = pinnedOrder.mapNotNull { byPkg[it] }
                .ifEmpty { visibleApps.take(6) }
            dockApps = loadedDock

            val reordering = reorderingPackageName
            if (reordering != null && reordering !in pinnedOrder) {
                exitDockReorderMode()
            } else if (reordering != null) {
                dockReorder.syncOrder(pinnedOrder)
                dockAdapter.setReorderingPackage(reordering)
            }
            dockAdapter.submitList(loadedDock) {
                if (reorderingPackageName != null) {
                    focusDockPackage(reorderingPackageName!!)
                }
            }
            allAppsController.updateApps(visibleApps, pinnedOrder.toSet())
            if (reorderingPackageName == null) {
                dockRecyclerView.post { ensureValidFocus() }
            }
        }
    }

    private fun launchApp(appInfo: AppInfo) {
        runCatching {
            val intent = Intent(appInfo.launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "Cannot open ${appInfo.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppContextMenu(appInfo: AppInfo, fromDock: Boolean) {
        val dlg = Dialog(this, R.style.Theme_SlateTV_Dialog)
        val pinned = app.dockPreferences.getPinnedPackages()
        val isPinned = appInfo.packageName in pinned

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@HomeActivity, R.drawable.bg_dialog_surface)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = ViewGroup.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(this).apply {
            text = appInfo.label
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })

        var firstOption: View? = null
        fun addOption(title: String, action: () -> Unit) {
            val row = TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@HomeActivity, R.drawable.bg_settings_row)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    dlg.dismiss()
                    action()
                }
            }
            if (firstOption == null) firstOption = row
            root.addView(row)
        }

        if (fromDock && isPinned) {
            addOption("Move / Reorder") {
                enterDockReorderMode(appInfo)
            }
            addOption("Unpin from Dock") {
                app.dockPreferences.unpin(appInfo.packageName)
                reloadApps()
            }
        } else {
            addOption(if (isPinned) "Unpin from Dock" else "Pin to Dock") {
                if (isPinned) app.dockPreferences.unpin(appInfo.packageName)
                else app.dockPreferences.pin(appInfo.packageName)
                reloadApps()
            }
        }

        addOption("Hide App") {
            app.dockPreferences.hide(appInfo.packageName)
            reloadApps()
        }

        addOption("App Info") {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${appInfo.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }

        dlg.setContentView(root)
        isContextMenuOpen = true
        mainHandler.removeCallbacks(enterIdleRunnable)
        dlg.setOnDismissListener {
            isContextMenuOpen = false
            resetIdleTimer()
        }
        dlg.show()
        firstOption?.requestFocus()
    }

    private fun handleDockReorderKey(event: KeyEvent): Boolean {
        return when (val result = dockReorder.onKey(event.keyCode, event.action, event.repeatCount)) {
            DockReorderController.KeyResult.Ignore -> false
            DockReorderController.KeyResult.Consume -> {
                reorderingPackageName?.let { focusDockPackage(it) }
                true
            }
            is DockReorderController.KeyResult.Moved -> {
                val pkg = reorderingPackageName ?: return true
                val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                app.dockPreferences.movePinned(pkg, delta)
                val persisted = app.dockPreferences.getPinnedPackages()
                if (persisted != dockReorder.order) dockReorder.syncOrder(persisted)
                if (!dockReorder.active || reorderingPackageName !in persisted) {
                    exitDockReorderMode()
                } else {
                    publishDockOrder(pkg)
                }
                true
            }
            is DockReorderController.KeyResult.Exit -> {
                exitDockReorderMode()
                true
            }
        }
    }

    private fun enterDockReorderMode(appInfo: AppInfo) {
        val pinned = app.dockPreferences.getPinnedPackages()
        if (!dockReorder.enter(appInfo.packageName, pinned)) return
        reorderingPackageName = appInfo.packageName
        reorderingLabel = appInfo.label
        dockAdapter.setReorderingPackage(appInfo.packageName)
        focusedLabelText.text = DockReorderController.prompt(appInfo.label)
        mainHandler.removeCallbacks(enterIdleRunnable)
        publishDockOrder(appInfo.packageName)
    }

    private fun exitDockReorderMode() {
        val pkg = reorderingPackageName ?: return
        val label = reorderingLabel
        reorderingPackageName = null
        reorderingLabel = null
        dockReorder.exit()
        if (::dockAdapter.isInitialized) dockAdapter.setReorderingPackage(null)
        if (::focusedLabelText.isInitialized) focusedLabelText.text = label.orEmpty()
        if (::dockRecyclerView.isInitialized) focusDockPackage(pkg)
        if (::settingsController.isInitialized) resetIdleTimer()
    }

    /**
     * Rebuilds the visible dock from the persisted pin order and the apps already loaded,
     * then locks focus on [packageName] at its new adapter index.
     */
    private fun publishDockOrder(packageName: String) {
        val known = (dockApps + dockAdapter.currentList).associateBy { it.packageName }
        val updated = dockReorder.order.mapNotNull { known[it] }
        if (updated.isEmpty()) {
            focusDockPackage(packageName)
            return
        }
        dockApps = updated
        val newIndex = updated.indexOfFirst { it.packageName == packageName }
        dockAdapter.submitList(updated) {
            if (newIndex < 0) return@submitList
            dockRecyclerView.scrollToPosition(newIndex)
            dockRecyclerView.findViewHolderForAdapterPosition(newIndex)?.itemView?.requestFocus()
            dockRecyclerView.post {
                dockRecyclerView.scrollToPosition(newIndex)
                dockRecyclerView.findViewHolderForAdapterPosition(newIndex)?.itemView?.requestFocus()
            }
        }
        reorderingLabel?.let { focusedLabelText.text = DockReorderController.prompt(it) }
    }

    private fun focusDockPackage(packageName: String) {
        val index = dockAdapter.currentList.indexOfFirst { it.packageName == packageName }
        if (index < 0) return
        dockRecyclerView.scrollToPosition(index)
        val focused = dockRecyclerView.findViewHolderForAdapterPosition(index)?.itemView
        if (focused?.hasFocus() != true) focused?.requestFocus()
        dockRecyclerView.post {
            dockRecyclerView.scrollToPosition(index)
            dockRecyclerView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }

    private fun enterIdleGalleryMode() {
        if (isIdleGalleryMode || isContextMenuOpen || reorderingPackageName != null ||
            allAppsController.isVisible || settingsController.isVisible
        ) {
            return
        }
        isIdleGalleryMode = true
        shelfController.clearPendingFocus()
        hudContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        hudContainer.clearFocus()
        hudContainer.animate()
            .alpha(0f)
            .setDuration(420L)
            .withEndAction {
                if (isIdleGalleryMode) {
                    hudContainer.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    private fun exitIdleGalleryMode() {
        if (!isIdleGalleryMode && hudContainer.alpha >= 0.99f && hudContainer.visibility == View.VISIBLE) return
        isIdleGalleryMode = false
        hudContainer.animate().cancel()
        hudContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (!allAppsController.isVisible && !settingsController.isVisible) {
            hudContainer.visibility = View.VISIBLE
        }
        hudContainer.animate().alpha(1f).setDuration(180L).start()
        ensureValidFocus()
    }

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(enterIdleRunnable)
        val idleSec = currentSettings.idleGallerySeconds
        if (idleSec > 0 && !isContextMenuOpen && reorderingPackageName == null &&
            !allAppsController.isVisible && !settingsController.isVisible
        ) {
            mainHandler.postDelayed(enterIdleRunnable, idleSec * 1_000L)
        }
    }

    private fun restoreDockFocus() {
        shelfController.clearPendingFocus()
        if (lastDockFocus?.takeIf { it.isAttachedToWindow && it.isShown }?.requestFocus() != true &&
            dockRecyclerView.getChildAt(0)?.requestFocus() != true) btnAllApps.requestFocus()
    }

    private fun ensureValidFocus() {
        val reordering = reorderingPackageName
        if (reordering != null) {
            focusDockPackage(reordering)
            return
        }
        FocusGuard.ensureHomeFocus(
            currentFocus = currentFocus,
            dockRecyclerView = dockRecyclerView,
            fallbackFocusView = btnAllApps,
            isOverlayOpen = allAppsController.isVisible || settingsController.isVisible || isIdleGalleryMode,
        )
    }

    private fun updateOfflineIndicator() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(active)
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        offlineBadge.visibility = if (connected) View.GONE else View.VISIBLE
    }

    private fun registerSystemObservers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    private fun unregisterSystemObservers() {
        runCatching { unregisterReceiver(packageChangeReceiver) }
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
