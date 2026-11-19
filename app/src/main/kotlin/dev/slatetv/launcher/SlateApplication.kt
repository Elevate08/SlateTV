package dev.slatetv.launcher

import android.app.Application
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class SlateApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        launcherPreferences = LauncherPreferences(this)
        dockPreferences = DockPreferences(this)
        appRepository = AppRepository(this)
        immichConfigStore = SharedPrefsImmichConfigStore(this)
        immichApiClient = ImmichApiClient()
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
        bitmapLoader = BitmapLoader(this, diskCache)
        wallpaperManager = WallpaperManager(
            scope = appScope,
            pluginRegistry = pluginRegistry,
            diskCache = diskCache,
            bitmapLoader = bitmapLoader,
        )
    }
}
