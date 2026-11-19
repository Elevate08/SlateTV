package com.stasisstate.slatetv.connector.extensions

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Auth headers for an asset URL, only when it lives on exactly the manifest's origin. */
private fun originLockedHeaders(manifest: ExtensionManifest, assetUrl: String, apiKey: String?): Map<String, String> {
    val header = AuthHeader.header(manifest.authHeaderName, apiKey) ?: return emptyMap()
    val manifestUrl = ExtensionUrlPolicy.parseHttpUrl(manifest.manifestUrl) ?: return emptyMap()
    val asset = ExtensionUrlPolicy.parseHttpUrl(assetUrl) ?: return emptyMap()
    return if (ExtensionUrlPolicy.sameOrigin(manifestUrl, asset)) mapOf(header) else emptyMap()
}

/** Background images from an extension's JSON feed. */
internal class ExtensionBackgroundPlugin(
    private val extension: InstalledExtension,
    private val client: ExtensionClient,
    private val apiKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : BackgroundPlugin {
    private val manifest = extension.manifest
    private val mutex = Mutex()
    private var cache: List<FeedItem>? = null
    private var expiresAt = 0L
    private var retryAt = 0L
    private val served = HashSet<String>()

    override val metadata = PluginMetadata(
        id = "extjson:${manifest.id}",
        name = manifest.name,
        description = manifest.description,
        version = manifest.version,
        isExternal = false,
        packageName = null,
        settingsActivityClassName = null,
        supportsNext = true,
    )

    override suspend fun isConfigured(): Boolean = manifest.backgroundUrl != null

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? = mutex.withLock {
        val items = refreshIfNeeded()
        if (items.isNullOrEmpty()) return@withLock null
        val item = pick(items, request.lastMediaId)
        BackgroundMedia(
            id = item.id,
            imageUri = item.imageUrl,
            headers = originLockedHeaders(manifest, item.imageUrl, apiKey()),
            title = item.title,
            subtitle = item.subtitle,
            width = item.width,
            height = item.height,
        )
    }

    private suspend fun refreshIfNeeded(): List<FeedItem>? {
        val now = clock()
        val cached = cache
        if (cached != null && now < expiresAt) return cached
        if (cached == null || now >= retryAt) {
            try {
                val fresh = withContext(Dispatchers.IO) { client.fetchBackground(manifest, apiKey()) }
                cache = fresh
                // An empty feed is re-checked after a short delay so an upstream fix shows up quickly.
                expiresAt = now + if (fresh.isEmpty()) RETRY_BACKOFF_MS else manifest.refreshIntervalSeconds * 1000L
                retryAt = 0L
                return fresh
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retryAt = now + RETRY_BACKOFF_MS
            }
        }
        return cache
    }

    /** Random order without repeats inside a pass; a new pass never starts with the item just shown. */
    private fun pick(items: List<FeedItem>, lastId: String?): FeedItem {
        var candidates = items.filter { it.id !in served }
        if (candidates.isEmpty()) {
            served.clear()
            candidates = items
        }
        val pool = if (candidates.size > 1 && lastId != null) {
            candidates.filter { it.id != lastId }.ifEmpty { candidates }
        } else candidates
        return pool[random.nextInt(pool.size)].also { served += it.id }
    }

    private companion object {
        const val RETRY_BACKOFF_MS = 30_000L
    }
}

/** A home shelf from an extension's JSON endpoint. */
internal class ExtensionShelfPlugin(
    private val extension: InstalledExtension,
    private val client: ExtensionClient,
    private val apiKey: () -> String?,
    hostPackageName: String = PACKAGE,
    private val clock: () -> Long = System::currentTimeMillis,
) : ShelfPlugin {
    private val manifest = extension.manifest
    private val mutex = Mutex()
    private var lastGood: ShelfData? = null
    private var lastGoodAt = 0L

    override val metadata = ShelfPluginMetadata(
        id = "extshelf:${manifest.id}",
        name = manifest.name,
        description = manifest.description,
        version = manifest.version,
        packageName = hostPackageName,
    )

    override suspend fun fetchShelf(): ShelfData? = mutex.withLock {
        val now = clock()
        lastGood?.let { if (now - lastGoodAt < CACHE_TTL_MS) return@withLock it }
        try {
            val key = apiKey()
            val fresh = withContext(Dispatchers.IO) { client.fetchShelf(manifest, key) }
            val shelf = fresh.copy(items = fresh.items.map { withImageHeaders(it, key) })
            lastGood = shelf
            lastGoodAt = now
            shelf
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastGood?.takeIf { now - lastGoodAt <= STALE_LIMIT_MS }
        }
    }

    private fun withImageHeaders(card: ShelfCard, key: String?): ShelfCard {
        val uri = card.imageUri ?: return card
        return card.copy(imageHeaders = originLockedHeaders(manifest, uri, key))
    }

    companion object {
        const val PACKAGE = "com.stasisstate.slatetv.connector.extensions"
        const val CACHE_TTL_MS = 30_000L
        const val STALE_LIMIT_MS = 90_000L
    }
}
