package dev.slatetv.launcher.wallpaper

import dev.slatetv.launcher.settings.LauncherSettings
import dev.slatetv.launcher.wallpaper.plugins.AmbientSlatePlugin
import dev.slatetv.launcher.wallpaper.plugins.PluginRegistry
import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WallpaperUiState(
    val backdrop: LoadedBackdrop? = null,
    val activePluginName: String = "Ambient Slate",
    val lastSyncStatus: String = "Ready",
    val isRefreshing: Boolean = false,
)

/**
 * Coordinates background plugins, rotation timer, prefetching, and instant disk hydration.
 *
 * Guarantees:
 * 1. Startup hydrates immediately from disk cache or ambient slate (zero network block).
 * 2. Network failures keep the current visible image intact (never clears to black).
 * 3. Calling [onNetworkRestored] immediately retries if the last fetch failed while Shield was waking.
 */
class WallpaperManager(
    private val scope: CoroutineScope,
    private val pluginRegistry: PluginRegistry,
    private val diskCache: WallpaperDiskCache,
    private val bitmapLoader: BitmapLoader,
) {
    private val _state = MutableStateFlow(WallpaperUiState())
    val state: StateFlow<WallpaperUiState> = _state.asStateFlow()

    private var rotationJob: Job? = null
    private var fetchJob: Job? = null
    private var currentSettings: LauncherSettings = LauncherSettings()
    private var targetWidth: Int = 1920
    private var targetHeight: Int = 1080
    private var lastMediaId: String? = null
    private var lastFetchFailed: Boolean = false
    private val prefetchedQueue = ArrayDeque<BackgroundMedia>()

    fun configureDisplay(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            targetWidth = width.coerceAtMost(1920)
            targetHeight = height.coerceAtMost(1080)
        }
    }

    /**
     * Step 1 on launch: Paint an instant local backdrop (last cached photo or Ambient Slate)
     * before doing any network work.
     */
    fun start(settings: LauncherSettings) {
        currentSettings = settings
        val plugin = pluginRegistry.getPluginById(settings.activePluginId)
        _state.value = _state.value.copy(activePluginName = plugin.metadata.name)

        scope.launch {
            // Hydrate instantly from last cached image if current plugin is non-ambient
            val hydrated = if (plugin.metadata.id != AmbientSlatePlugin.PLUGIN_ID) {
                val last = diskCache.getLastShown()
                if (last != null) {
                    bitmapLoader.loadCachedEntry(last, targetWidth, targetHeight, settings.smartPortraitBlur)
                } else {
                    null
                }
            } else {
                null
            }

            if (hydrated != null) {
                lastMediaId = hydrated.mediaId
                _state.value = _state.value.copy(
                    backdrop = hydrated,
                    lastSyncStatus = "Loaded from instant cache",
                )
            } else if (_state.value.backdrop == null) {
                // Guaranteed non-black immediate fallback
                val ambientBmp = bitmapLoader.renderAmbientGradient(
                    AmbientSlatePlugin.DEFAULT_URI,
                    640,
                    360,
                )
                _state.value = _state.value.copy(
                    backdrop = LoadedBackdrop(
                        mediaId = "ambient:initial",
                        primaryBitmap = ambientBmp,
                        blurredBackgroundBitmap = null,
                        subtitle = null,
                        isPortrait = false,
                    ),
                )
            }

            // Now refresh from active plugin asynchronously in background
            requestNext(forceFresh = false)
            restartRotationLoop()
        }
    }

    fun applySettings(settings: LauncherSettings, pluginChanged: Boolean) {
        currentSettings = settings
        val plugin = pluginRegistry.getPluginById(settings.activePluginId)
        _state.value = _state.value.copy(activePluginName = plugin.metadata.name)
        if (pluginChanged) {
            prefetchedQueue.clear()
            lastMediaId = null
            requestNext(forceFresh = true)
        }
        restartRotationLoop()
    }

    fun onResume() {
        restartRotationLoop()
        if (lastFetchFailed) {
            requestNext(forceFresh = false)
        }
    }

    fun onPause() {
        rotationJob?.cancel()
        rotationJob = null
    }

    fun onNetworkRestored() {
        if (lastFetchFailed || _state.value.backdrop?.mediaId == "ambient:initial") {
            requestNext(forceFresh = false)
        }
    }

    fun requestNext(forceFresh: Boolean = true) {
        if (fetchJob?.isActive == true && !forceFresh) return
        fetchJob?.cancel()
        fetchJob = scope.launch {
            val settings = currentSettings
            val plugin = pluginRegistry.getPluginById(settings.activePluginId)
            _state.value = _state.value.copy(isRefreshing = true)

            if (!plugin.isConfigured()) {
                val fallback = pluginRegistry.ambientPlugin.fetchNext(buildRequest())
                val loaded = fallback?.let {
                    bitmapLoader.load(it, targetWidth, targetHeight, settings.smartPortraitBlur)
                }
                _state.value = _state.value.copy(
                    backdrop = loaded ?: _state.value.backdrop,
                    isRefreshing = false,
                    lastSyncStatus = "${plugin.metadata.name} not configured yet",
                )
                return@launch
            }

            val nextMedia = if (prefetchedQueue.isNotEmpty()) {
                prefetchedQueue.removeFirst()
            } else {
                runCatching { plugin.fetchNext(buildRequest()) }.getOrNull()
            }

            if (nextMedia == null) {
                lastFetchFailed = true
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    lastSyncStatus = "Offline or server unreachable — keeping cached photo",
                )
                return@launch
            }

            val loaded = bitmapLoader.load(
                media = nextMedia,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                smartPortraitBlur = settings.smartPortraitBlur,
            )

            if (loaded != null) {
                lastFetchFailed = false
                lastMediaId = nextMedia.id
                if (nextMedia.imageUri.startsWith("http://") || nextMedia.imageUri.startsWith("https://")) {
                    diskCache.get(nextMedia.id)?.let { diskCache.markLastShown(it) }
                }
                _state.value = _state.value.copy(
                    backdrop = loaded,
                    isRefreshing = false,
                    lastSyncStatus = "Synced (${plugin.metadata.name})",
                )
                // Quietly prefetch next 2 images in background for instant future transitions
                launch { prefetchUpcoming(plugin) }
            } else {
                lastFetchFailed = true
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    lastSyncStatus = "Image load failed — keeping current backdrop",
                )
            }
        }
    }

    private suspend fun prefetchUpcoming(plugin: dev.slatetv.plugin.api.BackgroundPlugin) {
        if (plugin.metadata.id == AmbientSlatePlugin.PLUGIN_ID) return
        if (prefetchedQueue.size >= 2) return
        val upcoming = runCatching {
            plugin.prefetch(buildRequest(), count = 2)
        }.getOrDefault(emptyList())
        for (item in upcoming) {
            if (item.id != lastMediaId && prefetchedQueue.none { it.id == item.id }) {
                if (bitmapLoader.prefetchToDisk(item)) {
                    prefetchedQueue.addLast(item)
                }
            }
        }
    }

    private fun restartRotationLoop() {
        rotationJob?.cancel()
        val intervalSec = currentSettings.rotationIntervalSeconds
        if (intervalSec <= 0) return
        rotationJob = scope.launch {
            while (isActive) {
                delay(intervalSec * 1_000L)
                requestNext(forceFresh = false)
            }
        }
    }

    private fun buildRequest(): PluginRequestContext = PluginRequestContext(
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        preferLandscape = true,
        lastMediaId = lastMediaId,
    )
}
