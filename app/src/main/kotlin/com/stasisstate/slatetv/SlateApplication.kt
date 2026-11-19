package com.stasisstate.slatetv

import android.app.Application
import android.util.Log
import com.stasisstate.slatetv.apps.AppRepository
import com.stasisstate.slatetv.apps.DockPreferences
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

        pluginRegistry = PluginRegistry(
            context = this,
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
