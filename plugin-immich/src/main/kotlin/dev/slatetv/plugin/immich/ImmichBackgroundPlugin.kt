package dev.slatetv.plugin.immich

import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.BackgroundPlugin
import dev.slatetv.plugin.api.PluginMetadata
import dev.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * First-class Immich [BackgroundPlugin] implementation.
 *
 * Uses a small in-memory candidate queue so wallpaper rotation only hits the
 * Immich search/album endpoint once every ~20 rotations.
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
        }
    }

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? {
        val config = configStore.load()
        if (!config.isConfigured) return null

        val asset = queueMutex.withLock {
            val fingerprint = config.hashCode()
            if (fingerprint != lastConfigFingerprint) {
                pendingQueue.clear()
                lastConfigFingerprint = fingerprint
            }
            if (pendingQueue.isEmpty()) {
                val fetched = runCatching {
                    apiClient.fetchCandidateAssets(config, batchSize = DEFAULT_BATCH_SIZE)
                }.getOrDefault(emptyList())
                pendingQueue.addAll(fetched)
            }
            // Avoid repeating the immediately preceding asset if alternatives exist
            var candidate = pendingQueue.removeFirstOrNull()
            if (candidate != null && candidate.id == request.lastMediaId && pendingQueue.isNotEmpty()) {
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

        val result = mutableListOf<BackgroundMedia>()
        var lastId = request.lastMediaId
        repeat(count.coerceIn(1, 5)) {
            val next = fetchNext(request.copy(lastMediaId = lastId)) ?: return@repeat
            if (result.none { it.id == next.id }) {
                result += next
                lastId = next.id
            }
        }
        return result
    }

    private fun toBackgroundMedia(config: ImmichConfig, asset: ImmichAsset): BackgroundMedia {
        return BackgroundMedia(
            id = "immich:${asset.id}",
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
