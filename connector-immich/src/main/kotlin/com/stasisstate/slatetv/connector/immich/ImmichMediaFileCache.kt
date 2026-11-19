package com.stasisstate.slatetv.connector.immich

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val MAX_FILE_BYTES = 16L * 1024 * 1024
internal const val MAX_CACHE_BYTES = 160L * 1024 * 1024
internal const val MAX_CACHE_FILES = 24

/**
 * Bounded private LRU cache of downloaded Immich images (`<cacheDir>/connector_immich_media`), so the launcher
 * reads plain `file://` URIs while credentials and TOFU TLS stay inside the connector's own [OkHttpClient].
 *
 * Guarantees:
 * - Downloads stream to a `.download-<uuid>` temp file on [Dispatchers.IO] **outside** [cacheLock] and are
 *   cooperatively cancellable, even mid-body (a watchdog cancels the OkHttp call).
 * - A response is accepted only if it is 2xx, `image/` typed, non-empty and at most [maxFileBytes].
 * - Eviction runs only after a download fully succeeded, under [cacheLock], and never touches protected
 *   (recently handed-out) entries, so a failed or cancelled download can never evict anything.
 * - Publication is an atomic rename of the complete temp file.
 */
internal class ImmichMediaFileCache(
    private val folder: File,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
    private val maxCacheFiles: Int = MAX_CACHE_FILES,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {
    private val cacheLock = Any()

    /** Stable on-disk name for a cache key (any string, e.g. origin + quality + asset id). */
    fun nameFor(cacheKey: String): String = TofuTls.sha256Hex(cacheKey.toByteArray(Charsets.UTF_8))

    /** Returns the cached file for [cacheKey] (refreshing its LRU position) or null. */
    fun cached(cacheKey: String): File? = synchronized(cacheLock) {
        val file = File(folder, nameFor(cacheKey))
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else {
            null
        }
    }

    /**
     * Returns the cached image for [cacheKey], downloading it with [client] when missing.
     *
     * @param protectedNames entry names that must not be evicted (e.g. items recently handed to the launcher).
     * @param shouldPublish re-checked under the cache lock; when false the download is discarded and null returned.
     * @throws IOException when the response is not a cacheable image.
     */
    suspend fun fetch(
        cacheKey: String,
        client: OkHttpClient,
        request: Request,
        protectedNames: () -> Set<String> = { emptySet() },
        shouldPublish: () -> Boolean = { true },
    ): File? {
        cached(cacheKey)?.let { return it }
        val name = nameFor(cacheKey)
        val temp = File(folder, "$TEMP_PREFIX${UUID.randomUUID()}")
        try {
            val size = download(client, request, temp)
            return synchronized(cacheLock) {
                if (!shouldPublish()) return@synchronized null
                val target = File(folder, name)
                if (target.isFile && target.length() > 0) {
                    // A concurrent fetch already published this key (and may have handed it out): keep it.
                    target.setLastModified(System.currentTimeMillis())
                    return@synchronized target
                }
                evictLocked(incomingBytes = size, protectedNames = protectedNames() + name)
                if (!temp.renameTo(target)) throw IOException("Cannot publish cached media")
                target.setLastModified(System.currentTimeMillis())
                target
            }
        } finally {
            temp.delete()
        }
    }

    /** Names of all published entries (test/diagnostic helper). */
    fun entryNames(): Set<String> = synchronized(cacheLock) {
        folder.listFiles().orEmpty().filter { ENTRY_NAME.matches(it.name) }.map { it.name }.toSet()
    }

    private suspend fun download(client: OkHttpClient, request: Request, target: File): Long =
        withContext(Dispatchers.IO) {
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create media cache")
            val call = client.newCall(request)
            // A blocking socket read does not observe coroutine cancellation, so a child watchdog cancels the
            // call (closing its socket) as soon as this scope is cancelled.
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                with(ImmichApiClient) { call.executeCancellable() }.use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        throw IOException("Immich image request failed (HTTP ${response.code})")
                    }
                    if (body.contentType()?.type != "image") throw IOException("Immich did not return an image")
                    if (body.contentLength() > maxFileBytes) throw IOException("Immich image exceeds cache limit")

                    target.outputStream().use { output ->
                        val input = body.byteStream()
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > maxFileBytes) throw IOException("Immich image exceeds cache limit")
                            output.write(buffer, 0, read)
                        }
                        if (total == 0L) throw IOException("Empty Immich image")
                        ensureActive()
                        total
                    }
                }
            } catch (e: IOException) {
                // A cancelled call surfaces as IOException("Canceled"); report it as cancellation instead.
                ensureActive()
                throw e
            } finally {
                watchdog.cancel()
            }
        }

    private fun evictLocked(incomingBytes: Long, protectedNames: Set<String>) {
        val now = System.currentTimeMillis()
        val files = folder.listFiles().orEmpty()
        // Temp files orphaned by process death (never ones still being written by live downloads).
        files.filter { it.name.startsWith(TEMP_PREFIX) && now - it.lastModified() > STALE_TEMP_MS }.forEach { it.delete() }
        val cached = files.filter { ENTRY_NAME.matches(it.name) && it.exists() }
        var count = cached.size
        var bytes = cached.sumOf { it.length() }
        val evictable = cached.filter { it.name !in protectedNames }.sortedBy { it.lastModified() }
        for (oldest in evictable) {
            if (count + 1 <= maxCacheFiles && bytes + incomingBytes <= maxCacheBytes) break
            val size = oldest.length()
            if (!oldest.delete() && oldest.exists()) throw IOException("Cannot evict cached media")
            count--
            bytes -= size
        }
    }

    companion object {
        private const val TEMP_PREFIX = ".download-"
        private const val BUFFER_BYTES = 8192
        private const val STALE_TEMP_MS = 10 * 60 * 1000L
        internal val ENTRY_NAME = Regex("[0-9a-f]{64}")
    }
}
