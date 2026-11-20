package com.stasisstate.slatetv

import android.app.Application
import android.util.Log
import com.stasisstate.slatetv.apps.AppRepository
import com.stasisstate.slatetv.apps.DockPreferences
import com.stasisstate.slatetv.connector.extensions.BundledPlugins
import com.stasisstate.slatetv.connector.extensions.ExtensionRegistry
import com.stasisstate.slatetv.connector.extensions.PluginInstallState
import com.stasisstate.slatetv.connector.homeassistant.HomeAssistantRuntime
import com.stasisstate.slatetv.connector.immich.BuiltInImmichPlugin
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin
import com.stasisstate.slatetv.settings.LauncherPreferences
import com.stasisstate.slatetv.wallpaper.BitmapLoader
import com.stasisstate.slatetv.wallpaper.WallpaperDiskCache
import com.stasisstate.slatetv.wallpaper.WallpaperManager
import com.stasisstate.slatetv.wallpaper.plugins.AmbientSlatePlugin
import com.stasisstate.slatetv.wallpaper.plugins.LocalFolderPlugin
import com.stasisstate.slatetv.wallpaper.plugins.PluginRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class SlateApplication : Application() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SlateTV", "Contained uncaught launcher coroutine error", throwable)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    lateinit var sharedHttpClient: OkHttpClient
        private set
    lateinit var launcherPreferences: LauncherPreferences
        private set
    lateinit var dockPreferences: DockPreferences
        private set
    lateinit var appRepository: AppRepository
        private set
    lateinit var extensionRegistry: ExtensionRegistry
        private set
    lateinit var pluginRegistry: PluginRegistry
        private set
    lateinit var diskCache: WallpaperDiskCache
        private set
    lateinit var bitmapLoader: BitmapLoader
        private set
    lateinit var wallpaperManager: WallpaperManager
        private set

    override fun onCreate() {
        super.onCreate()
        // Share a single OkHttp connection/dispatcher pool across API & image loading
        sharedHttpClient = NetworkClientFactory.defaultClient()
        launcherPreferences = LauncherPreferences(this)
        dockPreferences = DockPreferences(this)
        appRepository = AppRepository(this)
        extensionRegistry = ExtensionRegistry(this)
        // Built-in plugins the plugin manager may uninstall/reinstall (their code always ships in the APK).
        PluginInstallState.supportedBuiltIns = setOf(
            BuiltInImmichPlugin.PLUGIN_ID,
            HomeAssistantRuntime.PLUGIN_ID,
            BuiltInLiveTvShelfPlugin.PLUGIN_ID,
        )
        val installState = PluginInstallState.get(this)
        PluginInstallState.addListener { id, installed ->
            if (id == HomeAssistantRuntime.PLUGIN_ID) HomeAssistantRuntime.setEnabled(installed)
        }
        // First-run install of bundled first-party plugins marked "preinstall" (never re-added once removed).
        BundledPlugins.seed(this)
        // One-time cleanup of the removed Jellyfin server Live TV source (credentials, key alias, cached logos).
        appScope.launch(Dispatchers.IO) {
            runCatching { com.stasisstate.slatetv.connector.livetv.LiveTvConfigStore(this@SlateApplication).migrateLegacyState() }
        }

        pluginRegistry = PluginRegistry(
            context = this,
            ambientPlugin = AmbientSlatePlugin(),
            localPlugin = LocalFolderPlugin(this),
            extraBuiltInPlugins = listOf(BuiltInImmichPlugin(this)),
            jsonExtensionProvider = { extensionRegistry.backgroundPlugins() },
            legacyBuiltInAliases = mapOf(
                BuiltInImmichPlugin.LEGACY_EXTERNAL_ID to BuiltInImmichPlugin.PLUGIN_ID,
            ),
            isBuiltInInstalled = installState::isBuiltInInstalled,
        ).apply {
            refreshExtensions()
        }

        diskCache = WallpaperDiskCache(File(cacheDir, "wallpapers"))
        bitmapLoader = BitmapLoader(this, diskCache, sharedHttpClient)
        wallpaperManager = WallpaperManager(
            scope = appScope,
            pluginRegistry = pluginRegistry,
            diskCache = diskCache,
            bitmapLoader = bitmapLoader,
        )

        // Home Assistant lives at application scope so doorbell rings arrive while other apps are in front.
        HomeAssistantRuntime.init(this, appScope, enabled = installState.isBuiltInInstalled(HomeAssistantRuntime.PLUGIN_ID))
    }
}
