package com.stasisstate.slatetv.plugin.immich

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * First-class Immich [BackgroundPlugin] implementation.
 *
 * Uses a small in-memory candidate queue so wallpaper rotation only hits the
 * Immich search endpoint once every ~24 rotations.
 */
class ImmichBackgroundPlugin(
    private val configStore: ImmichConfigStore,
    private val apiClient: ImmichApiClient = ImmichApiClient(),
) : BackgroundPlugin {

    private val queueMutex = Mutex()
    private val pendingQueue = ArrayDeque<ImmichAsset>()
    private var lastConfigFingerprint: Int = 0

    override val metadata: PluginMetadata = PluginMetadata(
        id = PLUGIN_ID,
        name = "Immich Photos",
        description = "Self-hosted photo library via x-api-key header authentication",
        version = "1.0.0",
        isExternal = false,
        supportsNext = true,
    )

    override suspend fun isConfigured(): Boolean = configStore.load().isConfigured

    suspend fun invalidateQueue() {
        queueMutex.withLock {
            pendingQueue.clear()
            apiClient.resetPagination()
        }
    }

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? {
        val config = configStore.load()
        if (!config.isConfigured) return null

        val asset = queueMutex.withLock {
            val fingerprint = config.hashCode()
            if (fingerprint != lastConfigFingerprint) {
                pendingQueue.clear()
                apiClient.resetPagination()
                lastConfigFingerprint = fingerprint
            }
            if (pendingQueue.isEmpty()) {
                val fetched = try {
                    apiClient.fetchCandidateAssets(config, batchSize = DEFAULT_BATCH_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                pendingQueue.addAll(fetched)
            }
            // Avoid repeating the immediately preceding asset if alternatives exist
            val prevAssetId = request.lastMediaId?.substringAfterLast(':')
            var candidate = pendingQueue.removeFirstOrNull()
            if (candidate != null && candidate.id == prevAssetId && pendingQueue.isNotEmpty()) {
                pendingQueue.addLast(candidate)
                candidate = pendingQueue.removeFirstOrNull()
            }
            candidate
        } ?: return null

        return toBackgroundMedia(config, asset)
    }

    override suspend fun prefetch(request: PluginRequestContext, count: Int): List<BackgroundMedia> {
        val config = configStore.load()
        if (!config.isConfigured) return emptyList()

        val target = count.coerceIn(1, 5)
        val assets = queueMutex.withLock {
            val fingerprint = config.hashCode()
            if (fingerprint != lastConfigFingerprint) {
                pendingQueue.clear()
                apiClient.resetPagination()
                lastConfigFingerprint = fingerprint
            }
            if (pendingQueue.isEmpty()) {
                val fetched = try {
                    apiClient.fetchCandidateAssets(config, batchSize = DEFAULT_BATCH_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                pendingQueue.addAll(fetched)
            }
            val taken = mutableListOf<ImmichAsset>()
            val seenIds = mutableSetOf<String>()
            val prevAssetId = request.lastMediaId?.substringAfterLast(':')
            while (taken.size < target && pendingQueue.isNotEmpty()) {
                var candidate = pendingQueue.removeFirst()
                if (taken.isEmpty() && candidate.id == prevAssetId && pendingQueue.isNotEmpty()) {
                    pendingQueue.addLast(candidate)
                    candidate = pendingQueue.removeFirst()
                }
                if (seenIds.add(candidate.id)) {
                    taken += candidate
                }
            }
            taken
        }
        return assets.map { toBackgroundMedia(config, it) }
    }

    private fun toBackgroundMedia(config: ImmichConfig, asset: ImmichAsset): BackgroundMedia {
        // Include rendition quality in ID so Preview <-> Fullsize switches invalidate cached image
        return BackgroundMedia(
            id = "immich:${config.imageQuality.apiSize}:${asset.id}",
            imageUri = apiClient.buildAssetImageUrl(config, asset.id),
            headers = apiClient.buildAuthHeaders(config),
            title = null,
            subtitle = asset.formatSubtitle(),
            width = asset.width,
            height = asset.height,
            allowUntrustedTls = config.allowUntrustedTls,
        )
    }

    companion object {
        const val PLUGIN_ID = "builtin.immich"
        private const val DEFAULT_BATCH_SIZE = 24
    }
}
