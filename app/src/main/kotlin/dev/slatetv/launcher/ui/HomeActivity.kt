package dev.slatetv.launcher.ui

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.slatetv.launcher.R
import dev.slatetv.launcher.SlateApplication
import dev.slatetv.launcher.apps.AppInfo
import dev.slatetv.launcher.settings.LauncherSettings
import dev.slatetv.plugin.immich.ImmichBackgroundPlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Slate TV Home Activity.
 *
 * Engineered specifically to eliminate black screens and wake-up lag on Nvidia Shield TV:
 * - Zero Accessibility Service required.
 * - Handles HDMI-CEC display configuration changes in-place without Activity recreation.
 * - Hydrates cached backdrop immediately (< 20ms) with guaranteed non-black gradient underneath.
 * - Automatically restores D-pad focus and retries network sync on wake-up.
 */
class HomeActivity : AppCompatActivity() {

    private val app: SlateApplication
        get() = application as SlateApplication

    private lateinit var backdropRenderer: BackdropRenderer
    private lateinit var hudContainer: View
    private lateinit var photoCaptionText: TextView
    private lateinit var offlineBadge: TextView
    private lateinit var homeClock: TextClock
    private lateinit var focusedLabelText: TextView
    private lateinit var dockRecyclerView: RecyclerView
    private lateinit var btnAllApps: ImageButton
    private lateinit var btnNextPhoto: ImageButton
    private lateinit var btnSettings: ImageButton

    private lateinit var dockAdapter: AppDockAdapter
    private lateinit var allAppsController: AllAppsOverlayController
    private lateinit var settingsController: SettingsDrawerController

    private var allInstalledApps: List<AppInfo> = emptyList()
    private var currentSettings: LauncherSettings = LauncherSettings()
    private var isIdleGalleryMode = false
    private var wallpaperCollectJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val enterIdleRunnable = Runnable { enterIdleGalleryMode() }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            app.pluginRegistry.refreshExternalPlugins()
            reloadApps()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                offlineBadge.visibility = View.GONE
                app.wallpaperManager.onNetworkRestored()
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
        setupDock()
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

    override fun onResume() {
        super.onResume()
        exitIdleGalleryMode()
        backdropRenderer.ensureVisibleAfterWake()
        app.wallpaperManager.onResume()
        updateOfflineIndicator()
        ensureValidFocus()
        resetIdleTimer()
    }

    override fun onPause() {
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
        val metrics = resources.displayMetrics
        app.wallpaperManager.configureDisplay(metrics.widthPixels, metrics.heightPixels)
        backdropRenderer.ensureVisibleAfterWake()
        ensureValidFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home closes any open overlay and restores the calm home screen
        if (allAppsController.isVisible) allAppsController.close()
        if (settingsController.isVisible) settingsController.close()
        exitIdleGalleryMode()
        ensureValidFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        wallpaperCollectJob?.cancel()
        mainHandler.removeCallbacks(enterIdleRunnable)
        unregisterSystemObservers()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                    if (!allAppsController.isVisible && !settingsController.isVisible) {
                        enterIdleGalleryMode()
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
        focusedLabelText = findViewById(R.id.focusedLabelText)
        dockRecyclerView = findViewById(R.id.dockRecyclerView)
        btnAllApps = findViewById(R.id.btnAllApps)
        btnNextPhoto = findViewById(R.id.btnNextPhoto)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun setupDock() {
        dockAdapter = AppDockAdapter(
            onAppClick = { launchApp(it) },
            onAppLongClick = { showAppContextMenu(it, fromDock = true) },
            onAppFocused = { focusedLabelText.text = it.label },
        )
        dockRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockRecyclerView.adapter = dockAdapter
        dockRecyclerView.itemAnimator = null
    }

    private fun setupActionPills() {
        configurePill(btnAllApps, "All Apps") {
            hudContainer.visibility = View.INVISIBLE
            allAppsController.open()
        }
        configurePill(btnNextPhoto, "Next Photo") {
            app.wallpaperManager.requestNext(forceFresh = true)
        }
        configurePill(btnSettings, "Settings") {
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
            overlayView = findViewById(R.id.settingsOverlay),
            itemsContainer = findViewById(R.id.settingsItemsContainer),
            statusSummaryView = findViewById(R.id.settingsStatusSummary),
            preferences = app.launcherPreferences,
            pluginRegistry = app.pluginRegistry,
            appRepository = app.appRepository,
            dockPreferences = app.dockPreferences,
            diskCache = app.diskCache,
            onSettingsChanged = { updated, pluginChanged, appsChanged ->
                currentSettings = updated
                applyUiPreferences(updated)
                app.wallpaperManager.applySettings(updated, pluginChanged)
                if (appsChanged) reloadApps()
            },
            onOpenImmichSetup = { openImmichSetup() },
            onClearCache = {
                app.diskCache.clear()
                Toast.makeText(this, "Photo cache cleared", Toast.LENGTH_SHORT).show()
            },
            onClosed = { ensureValidFocus() },
        )
    }

    private fun openImmichSetup() {
        ImmichSetupDialog(
            context = this,
            configStore = app.immichConfigStore,
            apiClient = app.immichApiClient,
        ) { _ ->
            app.appScope.launch {
                app.immichPlugin.invalidateQueue()
                val updated = app.launcherPreferences.update {
                    it.copy(activePluginId = ImmichBackgroundPlugin.PLUGIN_ID)
                }
                currentSettings = updated
                app.wallpaperManager.applySettings(updated, pluginChanged = true)
                if (settingsController.isVisible) {
                    settingsController.rebuild("Immich saved — syncing…")
                }
            }
        }.show()
    }

    private fun observeWallpaperState() {
        wallpaperCollectJob?.cancel()
        wallpaperCollectJob = app.appScope.launch {
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
        resetIdleTimer()
    }

    private fun reloadApps() {
        app.appScope.launch {
            val loaded = app.appRepository.loadInstalledApps(currentSettings.showSideloadedApps)
            allInstalledApps = loaded
            app.dockPreferences.seedDefaultsIfNeeded(loaded.map { it.packageName })

            val hidden = app.dockPreferences.getHiddenPackages()
            val visibleApps = loaded.filterNot { it.packageName in hidden }
            val byPkg = visibleApps.associateBy { it.packageName }

            val pinnedOrder = app.dockPreferences.getPinnedPackages()
            val dockApps = pinnedOrder.mapNotNull { byPkg[it] }
                .ifEmpty { visibleApps.take(6) }

            dockAdapter.submitList(dockApps)
            allAppsController.updateApps(visibleApps, pinnedOrder.toSet())
            dockRecyclerView.post { ensureValidFocus() }
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
            addOption("Move Left") {
                app.dockPreferences.movePinned(appInfo.packageName, -1)
                reloadApps()
            }
            addOption("Move Right") {
                app.dockPreferences.movePinned(appInfo.packageName, +1)
                reloadApps()
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
        dlg.show()
        firstOption?.requestFocus()
    }

    private fun enterIdleGalleryMode() {
        if (isIdleGalleryMode || allAppsController.isVisible || settingsController.isVisible) return
        isIdleGalleryMode = true
        hudContainer.animate().alpha(0f).setDuration(420L).start()
    }

    private fun exitIdleGalleryMode() {
        if (!isIdleGalleryMode && hudContainer.alpha >= 0.99f) return
        isIdleGalleryMode = false
        hudContainer.animate().cancel()
        hudContainer.animate().alpha(1f).setDuration(180L).start()
    }

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(enterIdleRunnable)
        val idleSec = currentSettings.idleGallerySeconds
        if (idleSec > 0 && !allAppsController.isVisible && !settingsController.isVisible) {
            mainHandler.postDelayed(enterIdleRunnable, idleSec * 1_000L)
        }
    }

    private fun ensureValidFocus() {
        FocusGuard.ensureHomeFocus(
            currentFocus = currentFocus,
            dockRecyclerView = dockRecyclerView,
            fallbackFocusView = btnAllApps,
            isOverlayOpen = allAppsController.isVisible || settingsController.isVisible,
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
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.registerDisplayListener(displayListener, mainHandler)
        }
    }

    private fun unregisterSystemObservers() {
        runCatching { unregisterReceiver(packageChangeReceiver) }
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        }
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(displayListener)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
