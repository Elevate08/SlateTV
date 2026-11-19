package com.stasisstate.slatetv.connector.extensions

import java.io.IOException

/** What an extension supplies to Slate TV. */
enum class ExtensionType(val wire: String) {
    BACKGROUND("background"),
    SHELF("shelf"),
    BOTH("both");

    val providesBackground: Boolean get() = this != SHELF
    val providesShelf: Boolean get() = this != BACKGROUND

    companion object {
        fun fromWire(value: String?): ExtensionType? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/**
 * A validated extension manifest. Endpoint URLs are stored already resolved to absolute,
 * same-origin URLs; [manifestUrl] is the URL the user supplied.
 */
data class ExtensionManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val type: ExtensionType,
    val manifestUrl: String,
    val backgroundUrl: String?,
    val shelfUrl: String?,
    val refreshIntervalSeconds: Int,
    val authHeaderName: String?,
    /** True when the URL served a bare photo feed and the manifest was synthesized. */
    val autoDetected: Boolean = false,
)

data class InstalledExtension(
    val manifest: ExtensionManifest,
    val enabled: Boolean,
    val installedAtEpochMs: Long,
    val hasApiKey: Boolean = false,
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
}

/** User-readable failure from fetching or validating extension data. [status] is the HTTP status, or 0. */
internal class ExtensionException(message: String, val status: Int = 0, cause: Throwable? = null) :
    IOException(message, cause)

internal data class FeedItem(
    val id: String,
    val imageUrl: String,
    val title: String?,
    val subtitle: String?,
    val width: Int,
    val height: Int,
)
