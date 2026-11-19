package com.stasisstate.slatetv.plugin.api

/**
 * Clean, platform-independent metadata describing a background plugin
 * (either built-in or discovered externally via AIDL).
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
 * - an `http://` or `https://` URL (fetched with [headers])
 * - a `file://` or `content://` URI
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
 * Unified interface implemented by both built-in plugins (Immich, Ambient Slate,
 * Local Photos) and the external AIDL plugin adapter.
 */
interface BackgroundPlugin {
    val metadata: PluginMetadata

    /** Whether the plugin currently has valid configuration to supply media. */
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
