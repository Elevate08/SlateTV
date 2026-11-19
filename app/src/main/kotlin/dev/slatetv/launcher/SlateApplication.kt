package dev.slatetv.launcher

import android.app.Application
import android.util.Log
import dev.slatetv.launcher.apps.AppRepository
import dev.slatetv.launcher.apps.DockPreferences
import dev.slatetv.launcher.settings.LauncherPreferences
import dev.slatetv.launcher.wallpaper.BitmapLoader
import dev.slatetv.launcher.wallpaper.WallpaperDiskCache
import dev.slatetv.launcher.wallpaper.WallpaperManager
import dev.slatetv.launcher.wallpaper.plugins.AmbientSlatePlugin
import dev.slatetv.launcher.wallpaper.plugins.LocalFolderPlugin
import dev.slatetv.launcher.wallpaper.plugins.PluginRegistry
import dev.slatetv.plugin.immich.ImmichApiClient
import dev.slatetv.plugin.immich.ImmichBackgroundPlugin
import dev.slatetv.plugin.immich.SharedPrefsImmichConfigStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    lateinit var immichConfigStore: SharedPrefsImmichConfigStore
        private set
    lateinit var immichApiClient: ImmichApiClient
        private set
    lateinit var immichPlugin: ImmichBackgroundPlugin
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
        sharedHttpClient = ImmichApiClient.defaultClient(allowUntrustedTls = false)
        launcherPreferences = LauncherPreferences(this)
        dockPreferences = DockPreferences(this)
        appRepository = AppRepository(this)
        immichConfigStore = SharedPrefsImmichConfigStore(this)
        immichApiClient = ImmichApiClient(sharedHttpClient)
        immichPlugin = ImmichBackgroundPlugin(immichConfigStore, immichApiClient)

        pluginRegistry = PluginRegistry(
            context = this,
            immichPlugin = immichPlugin,
            ambientPlugin = AmbientSlatePlugin(),
            localPlugin = LocalFolderPlugin(this),
        ).apply {
            refreshExternalPlugins()
        }

        diskCache = WallpaperDiskCache(File(cacheDir, "wallpapers"))
        bitmapLoader = BitmapLoader(this, diskCache, sharedHttpClient)
        wallpaperManager = WallpaperManager(
            scope = appScope,
            pluginRegistry = pluginRegistry,
            diskCache = diskCache,
            bitmapLoader = bitmapLoader,
        )
    }
}
