package com.stasisstate.slatetv.connector.extensions

import com.stasisstate.slatetv.plugin.api.ShelfData
import okhttp3.HttpUrl

/** Blocking protocol operations. Call from a background dispatcher. */
internal class ExtensionClient(val http: ExtensionHttp = ExtensionHttp()) {

    /**
     * Fetches and validates the manifest at [url]. [apiKey], when given, is sent only to the
     * origin of [url] (as Bearer and X-API-Key) because the manifest has not declared its header yet.
     */
    fun fetchManifest(url: String, apiKey: String?): ExtensionManifest {
        val parsed = ExtensionUrlPolicy.validateManifestUrl(url)
            ?: throw ExtensionException("Enter an https:// URL. http:// is only allowed for addresses on your local network.")
        val auth = listOfNotNull(
            AuthHeader.header("Authorization", apiKey),
            AuthHeader.header("X-API-Key", apiKey),
        )
        val result = http.get(parsed, auth)
        return ManifestParser.parse(result.body, parsed)
    }

    fun fetchBackground(manifest: ExtensionManifest, apiKey: String?): List<FeedItem> {
        val url = endpoint(manifest.backgroundUrl, manifest)
        return BackgroundFeedParser.parse(http.get(url, authFor(manifest, apiKey)).body)
    }

    fun fetchShelf(manifest: ExtensionManifest, apiKey: String?): ShelfData {
        val url = endpoint(manifest.shelfUrl, manifest)
        return ShelfFeedParser.parse(http.get(url, authFor(manifest, apiKey)).body, manifest.name)
    }

    /** One-line, user-readable result of exercising every endpoint the extension declares. */
    fun test(manifest: ExtensionManifest, apiKey: String?): String {
        val parts = ArrayList<String>()
        if (manifest.type.providesBackground) {
            parts += "Backgrounds OK · ${fetchBackground(manifest, apiKey).size} images"
        }
        if (manifest.type.providesShelf) {
            val shelf = fetchShelf(manifest, apiKey)
            parts += if (shelf.available) "Shelf OK · ${shelf.items.size} cards" else "Shelf reachable · ${shelf.emptyReason ?: "no cards right now"}"
        }
        return parts.joinToString(" · ")
    }

    private fun endpoint(value: String?, manifest: ExtensionManifest): HttpUrl {
        val manifestUrl = ExtensionUrlPolicy.validateManifestUrl(manifest.manifestUrl)
            ?: throw ExtensionException("Extension URL is no longer valid")
        return ExtensionUrlPolicy.resolveEndpoint(manifestUrl, value)
            ?: throw ExtensionException("Extension endpoint is invalid")
    }

    private fun authFor(manifest: ExtensionManifest, apiKey: String?): List<Pair<String, String>> =
        listOfNotNull(AuthHeader.header(manifest.authHeaderName, apiKey))
}
