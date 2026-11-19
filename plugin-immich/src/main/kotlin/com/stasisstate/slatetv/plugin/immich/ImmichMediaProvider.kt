package com.stasisstate.slatetv.plugin.immich

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

private const val AUTHORITY = "$IMMICH_PACKAGE.media"
private const val MEDIA_URI_PREFIX = "content://$AUTHORITY/"
internal const val MAX_FILE_BYTES = 16L * 1024 * 1024

/**
 * Headroom for every URI that may still be live: up to 8 queued items in [ImmichMediaBuffer],
 * the handoff grace window, and the item currently being displayed by Slate TV.
 */
internal const val MAX_CACHE_BYTES = 10 * MAX_FILE_BYTES
internal const val MAX_CACHE_FILES = 24
private val mediaName = Regex("[0-9a-f-]{36}")
private fun directory(context: Context) = File(context.cacheDir, "immich-media")
private fun mediaUri(name: String) = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()

/** Returns the cached file name behind a `content://<authority>/<uuid>` media URI, or null for any other URI. */
internal fun immichMediaNameOf(uri: String?): String? {
    if (uri == null || !uri.startsWith(MEDIA_URI_PREFIX)) return null
    return uri.substring(MEDIA_URI_PREFIX.length).takeIf { mediaName.matches(it) }
}

/**
 * Downloads Immich images into the provider-backed cache so Slate TV can read self-signed-TLS
 * media through a `content://` URI.
 *
 * Guarantees:
 * - Network downloads run on [Dispatchers.IO] **outside** [cacheLock] and are cooperatively
 *   cancellable (even mid-body via a call-cancellation watchdog).
 * - Failed downloads never evict existing cached files because eviction runs only after the full
 *   image is validated on disk and uses the actual downloaded byte length.
 * - Under [cacheLock], `shouldPublish` is re-checked and `protectedNames` is read right before
 *   eviction so invalidated refills never evict/publish/grant and live queued or grace-window URIs
 *   are never evicted or revoked.
 */
internal class ImmichMediaCache internal constructor(
    private val folder: File,
    private val api: ImmichApiClient,
    private val grantRead: (packageName: String, name: String) -> Unit,
    private val revokeRead: (name: String) -> Unit,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
    private val maxCacheFiles: Int = MAX_CACHE_FILES,
) {
    constructor(context: Context, api: ImmichApiClient) : this(
        folder = directory(context),
        api = api,
        grantRead = { pkg, name -> context.grantUriPermission(pkg, mediaUri(name), FLAG_GRANT_READ_URI_PERMISSION) },
        revokeRead = { name -> context.revokeUriPermission(mediaUri(name), FLAG_GRANT_READ_URI_PERMISSION) },
    )

    private val cacheLock = Any()

    suspend fun publish(
        config: ImmichConfig,
        media: BackgroundMedia,
        grantPackages: Iterable<String>,
        protectedNames: () -> Set<String> = { emptySet() },
        shouldPublish: () -> Boolean = { true },
    ): Uri? = publishName(config, media, grantPackages, protectedNames, shouldPublish)?.let(::mediaUri)

    /** Returns the published cache entry name, or `null` when [shouldPublish] rejected the download. */
    internal suspend fun publishName(
        config: ImmichConfig,
        media: BackgroundMedia,
        grantPackages: Iterable<String>,
        protectedNames: () -> Set<String> = { emptySet() },
        shouldPublish: () -> Boolean = { true },
    ): String? {
        val request = Request.Builder().url(media.imageUri).apply {
            media.headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val name = UUID.randomUUID().toString()
        val file = File(folder, ".download-$name")
        try {
            val size = download(api.clientFor(config).newCall(request), file)
            return synchronized(cacheLock) {
                if (!shouldPublish()) return@synchronized null
                evictLocked(incomingBytes = size, protectedNames = protectedNames())
                if (!file.renameTo(File(folder, name))) throw IOException("Cannot publish cached media")
                grantPackages.forEach { grantRead(it, name) }
                name
            }
        } finally {
            file.delete()
        }
    }

    /** Streams [call]'s image body into [target], returning its size. Cancels [call] if the coroutine is cancelled. */
    private suspend fun download(call: Call, target: File): Long = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create media cache")
        // A blocking socket read does not observe coroutine cancellation, so a child watchdog
        // cancels the call (closing its socket) as soon as this scope is cancelled.
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
                if (!response.isSuccessful || body == null || body.contentType()?.type != "image" ||
                    body.contentLength() > MAX_FILE_BYTES
                ) throw IOException("Immich did not return a cacheable image")

                target.outputStream().use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_FILE_BYTES) throw IOException("Immich image exceeds cache limit")
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw IOException("Empty Immich image")
                    ensureActive()
                    total
                }
            }
        } catch (e: IOException) {
            // A cancelled call surfaces as IOException("Canceled"); report it as cancellation.
            ensureActive()
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private fun evictLocked(incomingBytes: Long, protectedNames: Set<String>) {
        val cached = folder.listFiles().orEmpty().filter { mediaName.matches(it.name) }
        var count = cached.size
        var bytes = cached.sumOf { it.length() }
        val evictable = cached.filter { it.name !in protectedNames }.sortedBy { it.lastModified() }
        for (oldest in evictable) {
            if (count + 1 <= maxCacheFiles && bytes + incomingBytes <= maxCacheBytes) break
            val size = oldest.length()
            revokeRead(oldest.name)
            if (!oldest.delete() && oldest.exists()) throw IOException("Cannot evict cached media")
            count--
            bytes -= size
        }
    }
}

class ImmichMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.pathSegments.singleOrNull()
        if (mode != "r" || uri.authority != AUTHORITY || name == null || !mediaName.matches(name)) {
            throw FileNotFoundException()
        }
        return ParcelFileDescriptor.open(File(directory(requireNotNull(context)), name), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
