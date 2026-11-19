package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal const val LOGO_FETCH_PERMITS = 4
internal const val LOGO_FETCH_TIMEOUT_MS = 2_000L
internal const val MAX_LOGO_BYTES = 2L * 1024 * 1024
private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
private const val MAX_CACHE_FILES = 48
private val logoName = Regex("[0-9a-f]{32}")

/** Hash of server, user and API key; never contains or reveals the raw secret. */
internal fun logoNamespace(config: LiveTvConfig, userId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${config.serverUrl}\u0000$userId\u0000${config.apiKey}\u0000${config.pinnedCertDer}".toByteArray())
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

/** `file:///…` URI string for a cached logo file. */
internal fun fileUriString(file: File) = "file://${file.toURI().rawPath}"

/**
 * Bounded on-disk cache for channel logos. Logos are downloaded in-process (Jellyfin images use the
 * pinned-TLS authenticated client, so tokens never leave the app) and exposed as plain `file://` URIs.
 */
internal class LiveTvLogoCache(private val folder: File) {
    private val cacheLock = Any()

    suspend fun publishJellyfin(api: JellyfinApiClient, imageUrl: String, imageTag: String?, namespace: String): String? =
        publishCached(imageUrl, imageTag, namespace) { api.downloadImageBytes(imageUrl, MAX_LOGO_BYTES) }

    /** Publishes a System Live Channels logo under a daily revision tag so changed station logos eventually refresh. */
    suspend fun publishSystemLogo(logoUri: String, now: Long = System.currentTimeMillis(), load: () -> ByteArray?): String? =
        publishCached(logoUri, (now / 86_400_000L).toString(), "system-live-channels") { load() }

    suspend fun publishCached(
        imageUrl: String,
        imageTag: String?,
        namespace: String,
        download: suspend () -> ByteArray?,
    ): String? {
        val name = cacheKeyFor(namespace, imageUrl, imageTag)
        synchronized(cacheLock) {
            val file = File(folder, name)
            if (file.isFile && file.length() > 0L) {
                file.setLastModified(System.currentTimeMillis())
                return fileUriString(file)
            }
        }

        val bytes = download() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_LOGO_BYTES) return null

        return synchronized(cacheLock) {
            if (!folder.isDirectory && !folder.mkdirs()) return@synchronized null
            val target = File(folder, name)
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@synchronized fileUriString(target)
            }

            val cached = folder.listFiles().orEmpty()
                .filter { logoName.matches(it.name) }
                .sortedBy { it.lastModified() }
                .toMutableList()
            var totalBytes = cached.sumOf { it.length() }
            while (cached.isNotEmpty() && (cached.size >= MAX_CACHE_FILES || totalBytes + bytes.size > MAX_CACHE_BYTES)) {
                val oldest = cached.removeAt(0)
                val size = oldest.length()
                if (oldest.delete() || !oldest.exists()) totalBytes -= size else break
            }

            val temp = File(folder, ".tmp-$name-${UUID.randomUUID()}")
            try {
                temp.writeBytes(bytes)
                if (temp.renameTo(target)) fileUriString(target) else null
            } catch (_: Exception) {
                null
            } finally {
                temp.delete()
            }
        }
    }

    companion object {
        /** Credential-scoped cache key so a changed user/API key never reuses logos fetched under the old one. */
        internal fun cacheKeyFor(namespace: String, imageUrl: String, imageTag: String?): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$namespace|$imageUrl|${imageTag.orEmpty()}".toByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

internal class LogoProxyResult(val logos: Map<String, String?>, val hasMissingLogos: Boolean)

/**
 * Fetches logos with at most [semaphore] concurrent fetches. The per-item [timeoutMs] starts only
 * after a permit is acquired, so queued items still get their full budget once admitted.
 */
internal suspend fun <T> proxyLogos(
    channels: List<T>,
    idOf: (T) -> String,
    rawUrlOf: (T) -> String?,
    semaphore: Semaphore,
    timeoutMs: Long = LOGO_FETCH_TIMEOUT_MS,
    fetch: suspend (T, String) -> String?,
): LogoProxyResult {
    val results = coroutineScope {
        channels.map { channel ->
            async(Dispatchers.IO) {
                val rawUrl = rawUrlOf(channel)?.takeIf { it.length <= 2048 }
                    ?: return@async Triple(idOf(channel), null, rawUrlOf(channel) != null)
                val uri = semaphore.withPermit {
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            fetch(channel, rawUrl)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                Triple(idOf(channel), uri, uri == null)
            }
        }.awaitAll()
    }
    return LogoProxyResult(
        logos = results.associate { it.first to it.second },
        hasMissingLogos = results.any { it.third },
    )
}
