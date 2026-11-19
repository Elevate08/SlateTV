package com.stasisstate.slatetv.plugin.immich

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
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

internal class ImmichMediaCache(
    private val folder: File,
    private val api: ImmichApiClient,
    private val revoke: (String) -> Unit,
    private val grant: (String, Iterable<String>) -> Unit,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
    private val maxCacheFiles: Int = MAX_CACHE_FILES,
) {
    constructor(context: Context, api: ImmichApiClient) : this(
        folder = directory(context),
        api = api,
        revoke = { name -> context.revokeUriPermission(mediaUri(name), FLAG_GRANT_READ_URI_PERMISSION) },
        grant = { name, packages ->
            val uri = mediaUri(name)
            packages.forEach { context.grantUriPermission(it, uri, FLAG_GRANT_READ_URI_PERMISSION) }
        },
    )

    private val cacheLock = Any()

    fun publish(
        config: ImmichConfig,
        media: BackgroundMedia,
        grantPackages: Iterable<String>,
        protectedNames: Set<String> = emptySet(),
    ): Uri = mediaUri(publishName(config, media, grantPackages, protectedNames))

    /**
     * Downloads [media] into a private temp file and only then makes room for it, so a failed
     * download never evicts usable cached files. Files named in [protectedNames] (queued or
     * recently handed off to Slate TV) are never evicted or revoked; if only protected files
     * remain the cache temporarily exceeds its caps rather than breaking live URIs.
     */
    fun publishName(
        config: ImmichConfig,
        media: BackgroundMedia,
        grantPackages: Iterable<String>,
        protectedNames: Set<String> = emptySet(),
    ): String {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create media cache")
        val name = UUID.randomUUID().toString()
        val temp = File(folder, ".download-$name")
        try {
            val size = download(config, media, temp)
            synchronized(cacheLock) {
                evictFor(size, protectedNames)
                if (!temp.renameTo(File(folder, name))) throw IOException("Cannot publish cached media")
                grant(name, grantPackages)
            }
            return name
        } finally {
            temp.delete()
        }
    }

    private fun download(config: ImmichConfig, media: BackgroundMedia, target: File): Long {
        val request = Request.Builder().url(media.imageUri).apply {
            media.headers.forEach { (name, value) -> header(name, value) }
        }.build()
        api.clientFor(config).newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null || body.contentType()?.type != "image" ||
                body.contentLength() > MAX_FILE_BYTES
            ) throw IOException("Immich did not return a cacheable image")

            target.outputStream().use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    written += read
                    if (written > MAX_FILE_BYTES) throw IOException("Immich image exceeds cache limit")
                    output.write(buffer, 0, read)
                }
                if (written == 0L) throw IOException("Empty Immich image")
            }
        }
        return target.length().takeIf { it > 0 } ?: throw IOException("Empty Immich image")
    }

    private fun evictFor(incomingBytes: Long, protectedNames: Set<String>) {
        val cached = folder.listFiles().orEmpty().filter { mediaName.matches(it.name) }
        var count = cached.size
        var bytes = cached.sumOf { it.length() }
        val evictable = cached.filter { it.name !in protectedNames }.sortedBy { it.lastModified() }
        for (oldest in evictable) {
            if (count + 1 <= maxCacheFiles && bytes + incomingBytes <= maxCacheBytes) break
            val size = oldest.length()
            revoke(oldest.name)
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

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
