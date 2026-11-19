package com.stasisstate.slatetv.plugin.api

/**
 * Clean, platform-independent metadata describing a background photo provider
 * (built-in connector or zero-code HTTP/JSON extension).
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val isExternal: Boolean = false,
    val packageName: String? = null,
    val settingsActivityClassName: String? = null,
    val supportsNext: Boolean = true,
)

/**
 * Parameters describing the display and preferences for a media fetch.
 */
data class PluginRequestContext(
    val targetWidth: Int = 1920,
    val targetHeight: Int = 1080,
    val preferLandscape: Boolean = true,
    val lastMediaId: String? = null,
)

/**
 * A single background image/artwork item supplied by a [BackgroundPlugin].
 *
 * [imageUri] may be:
 * - an `http://` or `https://` URL (fetched with origin-bound [headers])
 * - a `file://` URI inside the launcher's storage/cache
 * - an `ambient://` URI for zero-network procedural gradients
 */
data class BackgroundMedia(
    val id: String,
    val imageUri: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val subtitle: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val allowUntrustedTls: Boolean = false,
) {
    val isPortrait: Boolean
        get() = width > 0 && height > 0 && height > width
}

/**
 * Unified interface implemented by built-in background providers (Ambient Slate, Local Photos,
 * Immich) and zero-code JSON background extensions.
 */
interface BackgroundPlugin {
    val metadata: PluginMetadata

    /** Whether the provider currently has valid configuration to supply media. */
    suspend fun isConfigured(): Boolean = true

    /** Fetch a single next background item, or null if temporarily unavailable. */
    suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia?

    /**
     * Fetch a batch of upcoming items for background prefetching/caching.
     * Defaults to calling [fetchNext] repeatedly.
     */
    suspend fun prefetch(request: PluginRequestContext, count: Int): List<BackgroundMedia> {
        val result = mutableListOf<BackgroundMedia>()
        var lastId = request.lastMediaId
        repeat(count.coerceAtLeast(1)) {
            val item = fetchNext(request.copy(lastMediaId = lastId)) ?: return@repeat
            if (result.none { it.id == item.id }) {
                result += item
                lastId = item.id
            }
        }
        return result
    }
}
