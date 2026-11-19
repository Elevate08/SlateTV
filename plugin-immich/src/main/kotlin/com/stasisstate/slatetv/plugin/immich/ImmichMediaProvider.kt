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
private const val MAX_FILE_BYTES = 16L * 1024 * 1024
private const val MAX_CACHE_BYTES = 5 * MAX_FILE_BYTES
private const val MAX_CACHE_FILES = 24
private val mediaName = Regex("[0-9a-f-]{36}")
private fun directory(context: Context) = File(context.cacheDir, "immich-media")
private fun mediaUri(name: String) = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()

internal class ImmichMediaCache(private val context: Context, private val api: ImmichApiClient) {
    private val cacheLock = Any()

    fun publish(config: ImmichConfig, media: BackgroundMedia, grantPackages: Iterable<String>): Uri = synchronized(cacheLock) {
        val folder = directory(context)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create media cache")
        val cached = folder.listFiles().orEmpty().filter { mediaName.matches(it.name) }
            .sortedBy { it.lastModified() }.toMutableList()
        var bytes = cached.sumOf { it.length() }
        while (cached.size >= MAX_CACHE_FILES || bytes + MAX_FILE_BYTES > MAX_CACHE_BYTES) {
            val oldest = cached.removeAt(0)
            val size = oldest.length()
            context.revokeUriPermission(mediaUri(oldest.name), FLAG_GRANT_READ_URI_PERMISSION)
            if (!oldest.delete() && oldest.exists()) throw IOException("Cannot evict cached media")
            bytes -= size
        }

        val request = Request.Builder().url(media.imageUri).apply {
            media.headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val name = UUID.randomUUID().toString()
        val file = File(folder, ".download-$name")
        try {
            api.clientFor(config).newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null || body.contentType()?.type != "image" ||
                    body.contentLength() > MAX_FILE_BYTES
                ) throw IOException("Immich did not return a cacheable image")

                file.outputStream().use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(8192)
                    var remaining = MAX_FILE_BYTES
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        remaining -= read
                        if (remaining < 0) throw IOException("Immich image exceeds cache limit")
                        output.write(buffer, 0, read)
                    }
                    if (remaining == MAX_FILE_BYTES) throw IOException("Empty Immich image")
                }
            }
            if (!file.renameTo(File(folder, name))) throw IOException("Cannot publish cached media")
            mediaUri(name).also { uri ->
                grantPackages.forEach { context.grantUriPermission(it, uri, FLAG_GRANT_READ_URI_PERMISSION) }
            }
        } finally {
            file.delete()
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
