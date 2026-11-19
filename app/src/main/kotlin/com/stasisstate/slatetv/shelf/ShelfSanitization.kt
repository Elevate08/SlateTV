package com.stasisstate.slatetv.shelf

import android.os.Bundle
import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPayload
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

private val componentName = Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")
private val className = Regex("\\.?[a-zA-Z_$][a-zA-Z0-9_$.]*")
private val headerName = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
private val safeContentPath = Regex("/[A-Za-z0-9._-]{1,128}")
private val blockedHeaders = setOf(
    "host", "connection", "content-length", "transfer-encoding", "upgrade", "proxy-authorization",
    "expect", "range", "accept-encoding", "te", "trailer",
)
private val allowedLaunchSchemes = setOf("http", "https", "jellyfin", "plex", "market")

internal fun activityClass(value: String?): String? =
    value?.takeIf { it.length <= 256 && className.matches(it) }

/**
 * Allows HTTP(S) URLs (without embedded userinfo) OR a content:// URI served exclusively by
 * the plugin's own `.media` ContentProvider (e.g., Immich photo cache or Jellyfin logo proxy).
 */
internal fun sanitizeImageUri(value: String?, ownerPackageName: String? = null): String? {
    val trimmed = value?.trim()?.takeIf { it.length <= 2048 } ?: return null
    if (trimmed.startsWith("content://")) {
        if (ownerPackageName.isNullOrBlank()) return null
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val expectedAuthority = "$ownerPackageName.media"
        val rawPath = parsed.rawPath.orEmpty()
        val validPath = safeContentPath.matches(rawPath) &&
            rawPath != "/." &&
            rawPath != "/.." &&
            !rawPath.contains("..")
        return trimmed.takeIf {
            parsed.scheme.equals("content", ignoreCase = true) &&
                parsed.authority == expectedAuthority &&
                validPath &&
                parsed.rawQuery == null &&
                parsed.rawFragment == null
        }
    }
    return trimmed.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
}

internal fun sanitizeHeaders(values: Sequence<Pair<String, Any?>>): Map<String, String> =
    values.take(16).mapNotNull { (name, value) ->
        val text = value as? String ?: return@mapNotNull null
        if (!headerName.matches(name) || name.lowercase() in blockedHeaders ||
            text.length > 2048 || text.any { it !in ' '..'~' }
        ) return@mapNotNull null
        name to text
    }.toMap()

@Suppress("DEPRECATION")
private fun Bundle.entries() = keySet().orEmpty().asSequence().map { it to get(it) }

private fun launchUri(value: String?): String? {
    if (value == null || value.length > 2048) return null
    val scheme = runCatching { URI(value).scheme?.lowercase() }.getOrNull() ?: return null
    return value.takeIf { scheme in allowedLaunchSchemes }
}

internal fun sanitizePayload(raw: ShelfPayload, metadata: ShelfPluginMetadata): ShelfData = ShelfData(
    available = raw.available,
    shelfTitle = raw.shelfTitle.take(120).ifBlank { metadata.name },
    emptyReason = raw.emptyReason?.take(160),
    guideActivityClassName = activityClass(raw.guideActivityClassName) ?: metadata.guideActivityClassName,
    items = if (!raw.available) emptyList() else raw.items.take(24).mapNotNull { item ->
        val title = item.title.trim().take(120)
        if (title.isEmpty()) return@mapNotNull null
        val safeImageUri = sanitizeImageUri(item.imageUri, metadata.packageName)
        ShelfCard(
            id = "${metadata.id}:${item.id.take(128)}",
            title = title,
            subtitle = item.subtitle?.take(160),
            channelNumber = item.channelNumber?.take(16),
            badge = item.badge?.take(32),
            imageUri = safeImageUri,
            imageHeaders = if (safeImageUri?.startsWith("content://") == true) {
                emptyMap()
            } else {
                sanitizeHeaders(item.imageHeaders.entries())
            },
            progressPercent = item.progressPercent.takeIf { it in 0..100 } ?: -1,
            startTimeEpochMs = item.startTimeEpochMs,
            endTimeEpochMs = item.endTimeEpochMs,
            launchPackageName = item.launchPackageName?.takeIf { it.length <= 256 && componentName.matches(it) },
            launchActivityClassName = activityClass(item.launchActivityClassName),
            launchUri = launchUri(item.launchUri),
            launchExtras = item.launchExtras.entries().take(16).mapNotNull { (key, value) ->
                if (key.isBlank() || key.length > 128) return@mapNotNull null
                when (value) {
                    is String -> key to value.take(2048)
                    is Boolean, is Int, is Long, is Double, is Float -> key to value
                    else -> null
                }
            }.toMap(),
        )
    }.distinctBy { it.id },
)
