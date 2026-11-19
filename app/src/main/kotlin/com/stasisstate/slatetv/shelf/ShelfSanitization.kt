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
private val blockedHeaders = setOf(
    "host", "connection", "content-length", "transfer-encoding", "upgrade", "proxy-authorization",
    "expect", "range", "accept-encoding", "te", "trailer",
)

internal fun activityClass(value: String?): String? =
    value?.takeIf { it.length <= 256 && className.matches(it) }

internal fun sanitizeImageUri(value: String?): String? =
    value?.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()

internal fun sanitizeHeaders(values: Sequence<Pair<String, Any?>>): Map<String, String> =
    values.take(16).mapNotNull { (name, value) ->
        val text = value as? String ?: return@mapNotNull null
        if (!headerName.matches(name) || name.lowercase() in blockedHeaders ||
            text.length > 2048 || text.any { it !in ' '..'~' }) return@mapNotNull null
        name to text
    }.toMap()

@Suppress("DEPRECATION")
private fun Bundle.entries() = keySet().orEmpty().asSequence().map { it to get(it) }

private fun launchUri(value: String?): String? {
    if (value == null || value.length > 2048) return null
    val scheme = runCatching { URI(value).scheme?.lowercase() }.getOrNull() ?: return null
    return value.takeUnless { scheme in setOf("file", "content", "intent", "android.resource", "javascript", "data") }
}

internal fun sanitizePayload(raw: ShelfPayload, metadata: ShelfPluginMetadata): ShelfData = ShelfData(
    available = raw.available,
    shelfTitle = raw.shelfTitle.take(120).ifBlank { metadata.name },
    emptyReason = raw.emptyReason?.take(160),
    guideActivityClassName = activityClass(raw.guideActivityClassName) ?: metadata.guideActivityClassName,
    items = if (!raw.available) emptyList() else raw.items.take(24).mapNotNull { item ->
        val title = item.title.trim().take(120)
        if (title.isEmpty()) return@mapNotNull null
        ShelfCard(
            id = "${metadata.id}:${item.id.take(128)}",
            title = title,
            subtitle = item.subtitle?.take(160),
            channelNumber = item.channelNumber?.take(16),
            badge = item.badge?.take(32),
            imageUri = sanitizeImageUri(item.imageUri),
            imageHeaders = sanitizeHeaders(item.imageHeaders.entries()),
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
