package com.stasisstate.slatetv.connector.immich

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Process-wide shared state so the settings screen can invalidate the running plugin's queue. */
internal object ImmichConnectorRuntime {
    val api: ImmichApiClient by lazy { ImmichApiClient() }
    private val generation = AtomicLong(0)

    val currentGeneration: Long get() = generation.get()

    fun invalidateAll() {
        generation.incrementAndGet()
    }
}

/**
 * Core media pipeline behind [BuiltInImmichPlugin]: a small candidate queue (one Immich search per ~24 rotations)
 * feeding downloads into [ImmichMediaFileCache]. Returned media point at private `file://` URIs with no headers.
 */
internal class ImmichMediaSource(
    private val store: ImmichConfigStore,
    private val api: ImmichApiClient,
    private val cache: ImmichMediaFileCache,
    private val generation: () -> Long = { ImmichConnectorRuntime.currentGeneration },
) {
    private val queueMutex = Mutex()
    private val pendingQueue = ArrayDeque<ImmichAsset>()
    private var lastFingerprint: Pair<Int, Long>? = null

    private val handoffLock = Any()
    private val recentHandoffs = ArrayDeque<String>()

    suspend fun isConfigured(): Boolean = loadConfig().isConfigured

    /** Prefs read + Keystore decrypt stay off the caller's (possibly main) thread. */
    private suspend fun loadConfig(): ImmichConfig = withContext(Dispatchers.IO) { store.load() }

    suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? {
        val config = loadConfig()
        if (!config.isConfigured) return null
        repeat(MAX_ATTEMPTS_PER_ITEM) {
            val asset = takeAssets(config, request.lastMediaId, 1).firstOrNull() ?: return null
            materialize(config, asset)?.let { return it }
        }
        return null
    }

    suspend fun prefetch(request: PluginRequestContext, count: Int): List<BackgroundMedia> {
        val config = loadConfig()
        if (!config.isConfigured) return emptyList()
        val target = count.coerceIn(1, MAX_PREFETCH)
        val result = mutableListOf<BackgroundMedia>()
        var attempts = 0
        var lastId = request.lastMediaId
        while (result.size < target && attempts < target * MAX_ATTEMPTS_PER_ITEM) {
            val asset = takeAssets(config, lastId, 1).firstOrNull() ?: break
            attempts++
            val media = materialize(config, asset) ?: continue
            // A duplicate means the (small) library wrapped around; stop instead of re-searching repeatedly.
            if (result.any { it.id == media.id }) break
            result += media
            lastId = media.id
        }
        return result
    }

    private suspend fun takeAssets(config: ImmichConfig, lastMediaId: String?, count: Int): List<ImmichAsset> =
        queueMutex.withLock {
            val fingerprint = config.hashCode() to generation()
            if (fingerprint != lastFingerprint) {
                pendingQueue.clear()
                api.resetPagination()
                lastFingerprint = fingerprint
            }
            if (pendingQueue.isEmpty()) {
                val fetched = try {
                    api.fetchCandidateAssets(config, batchSize = DEFAULT_BATCH_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                pendingQueue.addAll(fetched)
            }
            val prevAssetId = lastMediaId?.substringAfterLast(':')
            val taken = mutableListOf<ImmichAsset>()
            while (taken.size < count && pendingQueue.isNotEmpty()) {
                var candidate = pendingQueue.removeFirst()
                if (taken.isEmpty() && candidate.id == prevAssetId && pendingQueue.isNotEmpty()) {
                    pendingQueue.addLast(candidate)
                    candidate = pendingQueue.removeFirst()
                }
                taken += candidate
            }
            taken
        }

    /** Downloads [asset] into the private cache (outside the queue lock) and maps it to [BackgroundMedia]. */
    private suspend fun materialize(config: ImmichConfig, asset: ImmichAsset): BackgroundMedia? {
        val cacheKey = "${config.serverOrigin}|${config.imageQuality.apiSize}|${asset.id}"
        val request = Request.Builder()
            .url(api.buildAssetImageUrl(config, asset.id))
            .apply { api.buildAuthHeaders(config).forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val file = try {
            cache.fetch(
                cacheKey = cacheKey,
                client = api.clientFor(config),
                request = request,
                protectedNames = { synchronized(handoffLock) { recentHandoffs.toSet() } },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        recordHandoff(file.name)
        return BackgroundMedia(
            id = "immich:${config.imageQuality.apiSize}:${asset.id}",
            imageUri = fileUri(file),
            headers = emptyMap(),
            title = null,
            subtitle = asset.formatSubtitle(),
            width = asset.width,
            height = asset.height,
            allowUntrustedTls = false,
        )
    }

    private fun recordHandoff(name: String) = synchronized(handoffLock) {
        recentHandoffs.remove(name)
        recentHandoffs.addLast(name)
        while (recentHandoffs.size > MAX_PROTECTED_HANDOFFS) recentHandoffs.removeFirst()
    }

    companion object {
        private const val DEFAULT_BATCH_SIZE = 24
        private const val MAX_PREFETCH = 5
        private const val MAX_ATTEMPTS_PER_ITEM = 3
        private const val MAX_PROTECTED_HANDOFFS = 8

        fun fileUri(file: File): String = "file://${file.toURI().rawPath}"
    }
}
