package com.stasisstate.slatetv.connector.extensions

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Parses and validates Open JSON Extension Protocol v1 manifests, plus bare photo feeds. */
internal object ManifestParser {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_REFRESH_SECONDS = 900
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun validId(id: String?): Boolean = id != null && ID.matches(id)

    fun clampRefresh(seconds: Number?): Int =
        (seconds?.toLong() ?: DEFAULT_REFRESH_SECONDS.toLong()).coerceIn(60L, 86_400L).toInt()

    /** [sourceUrl] is the URL the user supplied (not the post-redirect URL). */
    fun parse(body: String, sourceUrl: HttpUrl): ExtensionManifest {
        val value = try {
            JSONTokener(body.trim()).nextValue()
        } catch (e: Exception) {
            throw ExtensionException("The URL did not return valid JSON")
        }
        return when {
            value is JSONArray -> wrapSimpleFeed(value, sourceUrl)
            value is JSONObject && value.has("schemaVersion") -> parseV1(value, sourceUrl)
            value is JSONObject && value.opt("items") is JSONArray -> wrapSimpleFeed(value.getJSONArray("items"), sourceUrl)
            else -> throw ExtensionException("Not an extension manifest or photo feed")
        }
    }

    private fun parseV1(json: JSONObject, source: HttpUrl): ExtensionManifest {
        val version = (json.opt("schemaVersion") as? Number)?.toInt()
        if (version != SCHEMA_VERSION) throw ExtensionException("Unsupported extension schema version")
        val id = (json.opt("id") as? String)?.trim()
        if (!validId(id)) throw ExtensionException("Manifest id is missing or invalid")
        val name = ExtensionUrlPolicy.text(json.opt("name") as? String, 80)
            ?: throw ExtensionException("Manifest name is missing")
        val type = ExtensionType.fromWire(json.opt("type") as? String)
            ?: throw ExtensionException("Manifest type must be background, shelf or both")
        val authHeader = (json.opt("authHeaderName") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (authHeader != null && !AuthHeader.validName(authHeader)) {
            throw ExtensionException("Manifest authHeaderName is not allowed")
        }
        fun endpoint(key: String): String? {
            val raw = json.opt(key) as? String ?: return null
            return ExtensionUrlPolicy.resolveEndpoint(source, raw)?.toString()
                ?: throw ExtensionException("$key must be on the same origin as the manifest")
        }
        val background = if (type.providesBackground) {
            endpoint("backgroundEndpoint") ?: throw ExtensionException("backgroundEndpoint is required for this type")
        } else null
        val shelf = if (type.providesShelf) {
            endpoint("shelfEndpoint") ?: throw ExtensionException("shelfEndpoint is required for this type")
        } else null
        return ExtensionManifest(
            id = id!!,
            name = name,
            description = ExtensionUrlPolicy.text(json.opt("description") as? String, 300).orEmpty(),
            version = ExtensionUrlPolicy.text(json.opt("version") as? String, 32) ?: "1",
            author = ExtensionUrlPolicy.text(json.opt("author") as? String, 80) ?: "Unknown",
            type = type,
            manifestUrl = source.toString(),
            backgroundUrl = background,
            shelfUrl = shelf,
            refreshIntervalSeconds = clampRefresh(json.opt("refreshIntervalSeconds") as? Number),
            authHeaderName = authHeader,
        )
    }

    private fun wrapSimpleFeed(items: JSONArray, source: HttpUrl): ExtensionManifest {
        val valid = (0 until minOf(items.length(), 200)).any { BackgroundFeedParser.parseItem(items.opt(it), it) != null }
        if (!valid) throw ExtensionException("The feed contains no usable image URLs")
        val host = source.host
        val safeHost = host.replace(Regex("[^A-Za-z0-9.-]"), "-")
        return ExtensionManifest(
            id = ("feed-" + safeHost.take(100) + "-" + ExtensionUrlPolicy.shortHash(source.toString())),
            name = ExtensionUrlPolicy.text(host, 80) ?: "Photo feed",
            description = "Photo feed from $host",
            version = "1",
            author = "Unknown",
            type = ExtensionType.BACKGROUND,
            manifestUrl = source.toString(),
            backgroundUrl = source.toString(),
            shelfUrl = null,
            refreshIntervalSeconds = DEFAULT_REFRESH_SECONDS,
            authHeaderName = null,
            autoDetected = true,
        )
    }
}
