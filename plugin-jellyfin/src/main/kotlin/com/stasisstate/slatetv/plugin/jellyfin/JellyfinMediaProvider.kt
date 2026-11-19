package com.stasisstate.slatetv.plugin.jellyfin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID

internal const val JELLYFIN_PACKAGE = "com.stasisstate.slatetv.plugin.jellyfin"
private const val AUTHORITY = "$JELLYFIN_PACKAGE.media"
private const val MAX_LOGO_BYTES = 2L * 1024 * 1024
private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
private const val MAX_CACHE_FILES = 48
private val logoName = Regex("[0-9a-f]{32}")

private fun logoDirectory(context: Context) = File(context.cacheDir, "jellyfin-logos")
private fun logoUri(name: String) = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()

/**
 * Bounded disk cache and ContentProvider proxy for Jellyfin channel logos.
 *
 * Downloads channel icons inside the Jellyfin plugin using its LAN-safe / self-signed-TLS
 * authenticated client, then exposes read-only `content://` URIs to Slate TV so Jellyfin
 * API tokens never cross IPC boundaries and self-signed HTTPS logos render reliably.
 */
internal class JellyfinLogoCache(private val context: Context) {
    private val cacheLock = Any()

    suspend fun publish(
        api: JellyfinApiClient,
        imageUrl: String,
        imageTag: String?,
        grantPackage: String,
    ): Uri? {
        val name = cacheKey("$imageUrl|${imageTag.orEmpty()}")
        val folder = logoDirectory(context)
        val existing = synchronized(cacheLock) {
            val file = File(folder, name)
            if (file.isFile && file.length() > 0L) {
                file.setLastModified(System.currentTimeMillis())
                val uri = logoUri(name)
                context.grantUriPermission(grantPackage, uri, FLAG_GRANT_READ_URI_PERMISSION)
                uri
            } else {
                null
            }
        }
        if (existing != null) return existing

        val bytes = api.downloadImageBytes(imageUrl, MAX_LOGO_BYTES) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_LOGO_BYTES) return null

        return synchronized(cacheLock) {
            if (!folder.isDirectory && !folder.mkdirs()) return@synchronized null
            val target = File(folder, name)
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@synchronized logoUri(name).also { uri ->
                    context.grantUriPermission(grantPackage, uri, FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val cached = folder.listFiles().orEmpty()
                .filter { logoName.matches(it.name) }
                .sortedBy { it.lastModified() }
                .toMutableList()
            var totalBytes = cached.sumOf { it.length() }
            while (cached.isNotEmpty() && (cached.size >= MAX_CACHE_FILES || totalBytes + bytes.size > MAX_CACHE_BYTES)) {
                val oldest = cached.removeAt(0)
                val size = oldest.length()
                context.revokeUriPermission(logoUri(oldest.name), FLAG_GRANT_READ_URI_PERMISSION)
                if (oldest.delete() || !oldest.exists()) {
                    totalBytes -= size
                } else {
                    break
                }
            }

            val temp = File(folder, ".tmp-$name-${UUID.randomUUID()}")
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) return@synchronized null
                logoUri(name).also { uri ->
                    context.grantUriPermission(grantPackage, uri, FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {
                null
            } finally {
                temp.delete()
            }
        }
    }

    private fun cacheKey(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

class JellyfinMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.pathSegments.singleOrNull()
        if (mode != "r" || uri.authority != AUTHORITY || name == null || !logoName.matches(name)) {
            throw FileNotFoundException()
        }
        val file = File(logoDirectory(requireNotNull(context)), name)
        if (!file.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
