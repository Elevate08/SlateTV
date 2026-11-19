package dev.slatetv.launcher.wallpaper

import dev.slatetv.launcher.settings.LauncherSettings
import dev.slatetv.launcher.wallpaper.plugins.AmbientSlatePlugin
import dev.slatetv.launcher.wallpaper.plugins.PluginRegistry
import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class WallpaperUiState(
    val backdrop: LoadedBackdrop? = null,
    val activePluginName: String = "Ambient Slate",
    val lastSyncStatus: String = "Ready",
    val isRefreshing: Boolean = false,
)

/**
 * Coordinates background plugins, rotation timer, prefetching, instant per-plugin disk hydration,
 * and bounded wake-up backoff retries.
 *
 * Guarantees:
 * 1. Startup hydrates immediately from per-plugin disk cache or ambient slate (zero network block).
 * 2. Network failures keep the current visible image intact (never clears to black).
 * 3. Generation counter prevents stale hydrations/fetches from overwriting newer plugin switches.
 * 4. Bounded backoff retry (3s -> 8s -> 20s -> 60s) recovers automatically when Shield DHCP/LAN
 *    or an Immich server comes back up, even when periodic rotation is set to Manual.
 */
class WallpaperManager(
    private val scope: CoroutineScope,
    private val pluginRegistry: PluginRegistry,
    private val diskCache: WallpaperDiskCache,
    private val bitmapLoader: BitmapLoader,
) {
    private val _state = MutableStateFlow(WallpaperUiState())
    val state: StateFlow<WallpaperUiState> = _state.asStateFlow()

    private val generation = AtomicLong(0L)
    private var startJob: Job? = null
    private var rotationJob: Job? = null
    private var fetchJob: Job? = null
    private var retryJob: Job? = null

    private var isForeground: Boolean = true
    private var currentSettings: LauncherSettings = LauncherSettings()
    private var targetWidth: Int = 1920
    private var targetHeight: Int = 1080
    private var lastMediaId: String? = null
    private var lastFetchFailed: Boolean = false
    private var consecutiveFailures: Int = 0
    private val prefetchedQueue = ArrayDeque<BackgroundMedia>()

    fun configureDisplay(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            targetWidth = width.coerceAtMost(1920)
            targetHeight = height.coerceAtMost(1080)
        }
    }

    fun start(settings: LauncherSettings) {
        currentSettings = settings
        isForeground = true
        val gen = generation.incrementAndGet()
        val plugin = pluginRegistry.getPluginById(settings.activePluginId)
        _state.value = _state.value.copy(activePluginName = plugin.metadata.name)

        startJob?.cancel()
        retryJob?.cancel()
        startJob = scope.launch {
            val hydrated = if (plugin.metadata.id != AmbientSlatePlugin.PLUGIN_ID) {
                val last = withContext(Dispatchers.IO) { diskCache.getLastShown(plugin.metadata.id) }
                if (last != null) {
                    bitmapLoader.loadCachedEntry(last, targetWidth, targetHeight, settings.smartPortraitBlur)
                } else {
                    null
                }
            } else {
                null
            }

            if (gen != generation.get()) return@launch

            if (hydrated != null) {
                lastMediaId = hydrated.mediaId
                _state.value = _state.value.copy(
                    backdrop = hydrated,
                    lastSyncStatus = "Loaded from instant cache",
                )
            } else if (_state.value.backdrop == null) {
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
                        smartPortraitBlurApplied = false,
                    ),
                )
            }

            if (isForeground) {
                requestNext(forceFresh = false)
                restartRotationLoop()
            }
        }
    }

    fun applySettings(settings: LauncherSettings, pluginChanged: Boolean) {
        val portraitBlurChanged = settings.smartPortraitBlur != currentSettings.smartPortraitBlur
        currentSettings = settings
        val plugin = pluginRegistry.getPluginById(settings.activePluginId)
        _state.value = _state.value.copy(activePluginName = plugin.metadata.name)

        if (pluginChanged) {
            generation.incrementAndGet()
            startJob?.cancel()
            retryJob?.cancel()
            prefetchedQueue.clear()
            lastMediaId = null
            consecutiveFailures = 0
            lastFetchFailed = false
            requestNext(forceFresh = true)
        } else if (portraitBlurChanged) {
            // Re-render current cached entry so Smart Portrait Blur toggle takes effect immediately
            scope.launch {
                val last = withContext(Dispatchers.IO) { diskCache.getLastShown(plugin.metadata.id) }
                if (last != null) {
                    val reloaded = bitmapLoader.loadCachedEntry(
                        last,
                        targetWidth,
                        targetHeight,
                        settings.smartPortraitBlur,
                    )
                    if (reloaded != null) {
                        _state.value = _state.value.copy(backdrop = reloaded)
                    }
                }
            }
        }
        if (isForeground) {
            restartRotationLoop()
        }
    }

    fun onResume() {
        isForeground = true
        restartRotationLoop()
        if (lastFetchFailed) {
            consecutiveFailures = 0
            requestNext(forceFresh = false)
        }
    }

    fun onPause() {
        isForeground = false
        rotationJob?.cancel()
        rotationJob = null
        retryJob?.cancel()
        retryJob = null
    }

    fun onNetworkRestored() {
        if (!isForeground) return
        if (lastFetchFailed || _state.value.backdrop?.mediaId == "ambient:initial") {
            consecutiveFailures = 0
            retryJob?.cancel()
            requestNext(forceFresh = false)
        }
    }

    fun requestNext(forceFresh: Boolean = true) {
        if (fetchJob?.isActive == true && !forceFresh) return
        val gen = generation.get()
        fetchJob?.cancel()
        retryJob?.cancel()
        fetchJob = scope.launch {
            val settings = currentSettings
            val plugin = pluginRegistry.getPluginById(settings.activePluginId)
            _state.value = _state.value.copy(isRefreshing = true)

            val configured = try {
                plugin.isConfigured()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

            if (gen != generation.get()) return@launch

            if (!configured) {
                val fallback = try {
                    pluginRegistry.ambientPlugin.fetchNext(buildRequest())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val loaded = fallback?.let {
                    bitmapLoader.load(it, targetWidth, targetHeight, settings.smartPortraitBlur)
                }
                if (gen != generation.get()) return@launch
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
                try {
                    plugin.fetchNext(buildRequest())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }

            if (gen != generation.get()) return@launch

            if (nextMedia == null) {
                recordFailure("No photo returned or server unreachable — retrying")
                return@launch
            }

            val loaded = try {
                bitmapLoader.load(
                    media = nextMedia,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    smartPortraitBlur = settings.smartPortraitBlur,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (gen != generation.get()) return@launch

            if (loaded != null) {
                lastFetchFailed = false
                consecutiveFailures = 0
                lastMediaId = nextMedia.id
                if (nextMedia.imageUri.startsWith("http://") ||
                    nextMedia.imageUri.startsWith("https://") ||
                    nextMedia.imageUri.startsWith("content://")
                ) {
                    val cacheKey = bitmapLoader.cacheKeyFor(nextMedia)
                    withContext(Dispatchers.IO) {
                        diskCache.get(cacheKey)?.let { diskCache.markLastShown(plugin.metadata.id, it) }
                    }
                }
                _state.value = _state.value.copy(
                    backdrop = loaded,
                    isRefreshing = false,
                    lastSyncStatus = "Synced (${plugin.metadata.name})",
                )
                launch { prefetchUpcoming(plugin, gen) }
            } else {
                recordFailure("Image load failed — keeping cached backdrop")
            }
        }
    }

    private fun recordFailure(status: String) {
        lastFetchFailed = true
        consecutiveFailures++
        _state.value = _state.value.copy(
            isRefreshing = false,
            lastSyncStatus = status,
        )
        scheduleBackoffRetry()
    }

    private fun scheduleBackoffRetry() {
        if (!isForeground) return
        val backoffSeconds = RETRY_BACKOFF_SECONDS.getOrNull(consecutiveFailures - 1) ?: return
        retryJob?.cancel()
        val gen = generation.get()
        retryJob = scope.launch {
            delay(backoffSeconds * 1_000L)
            if (isActive && isForeground && lastFetchFailed && gen == generation.get()) {
                requestNext(forceFresh = false)
            }
        }
    }

    private suspend fun prefetchUpcoming(plugin: dev.slatetv.plugin.api.BackgroundPlugin, gen: Long) {
        if (plugin.metadata.id == AmbientSlatePlugin.PLUGIN_ID) return
        if (prefetchedQueue.size >= 2) return
        val upcoming = try {
            plugin.prefetch(buildRequest(), count = 2)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (gen != generation.get()) return
        for (item in upcoming) {
            if (gen != generation.get()) return
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
        if (intervalSec <= 0 || !isForeground) return
        rotationJob = scope.launch {
            while (isActive) {
                delay(intervalSec * 1_000L)
                if (isForeground) {
                    requestNext(forceFresh = false)
                }
            }
        }
    }

    private fun buildRequest(): PluginRequestContext = PluginRequestContext(
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        preferLandscape = true,
        lastMediaId = lastMediaId,
    )

    companion object {
        internal val RETRY_BACKOFF_SECONDS = listOf(3, 8, 20, 60)
    }
}
