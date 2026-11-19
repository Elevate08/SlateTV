package dev.slatetv.launcher.wallpaper

import dev.slatetv.plugin.api.BackgroundMedia
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Bounded LRU disk cache for background images + metadata sidecars.
 *
 * Key Nvidia Shield reliability guarantee:
 * Stores a `last_shown.json` pointer to the most recently displayed wallpaper so
 * cold boots and HDMI-CEC wake-ups render immediately from local flash in < 20ms
 * with ZERO network I/O on the critical path.
 */
class WallpaperDiskCache(
    private val cacheDir: File,
    private val maxBytes: Long = 48L * 1024L * 1024L,
    private val maxEntries: Int = 20,
) {
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    data class CachedEntry(
        val mediaId: String,
        val imageFile: File,
        val subtitle: String?,
        val width: Int,
        val height: Int,
    )

    fun fileForMediaId(mediaId: String): File {
        val key = sha256Short(mediaId)
        return File(cacheDir, "$key.img")
    }

    private fun metaFileForMediaId(mediaId: String): File {
        val key = sha256Short(mediaId)
        return File(cacheDir, "$key.json")
    }

    private fun lastShownPointer(): File = File(cacheDir, "last_shown.json")

    @Synchronized
    fun put(media: BackgroundMedia, bytes: ByteArray): CachedEntry {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val imgFile = fileForMediaId(media.id)
        val tmpFile = File(cacheDir, "${imgFile.name}.tmp")
        tmpFile.writeBytes(bytes)
        if (!tmpFile.renameTo(imgFile)) {
            imgFile.writeBytes(bytes)
            tmpFile.delete()
        }
        val now = System.currentTimeMillis()
        imgFile.setLastModified(now)

        val metaJson = JSONObject().apply {
            put("id", media.id)
            put("subtitle", media.subtitle ?: JSONObject.NULL)
            put("width", media.width)
            put("height", media.height)
        }
        metaFileForMediaId(media.id).writeText(metaJson.toString())

        evictIfNeeded(protectFileName = imgFile.name)

        return CachedEntry(
            mediaId = media.id,
            imageFile = imgFile,
            subtitle = media.subtitle,
            width = media.width,
            height = media.height,
        )
    }

    @Synchronized
    fun get(mediaId: String): CachedEntry? {
        val imgFile = fileForMediaId(mediaId)
        if (!imgFile.exists() || imgFile.length() <= 0L) return null
        imgFile.setLastModified(System.currentTimeMillis())
        val meta = readMetadata(metaFileForMediaId(mediaId), fallbackId = mediaId)
        return CachedEntry(
            mediaId = meta.first,
            imageFile = imgFile,
            subtitle = meta.second,
            width = meta.third.first,
            height = meta.third.second,
        )
    }

    @Synchronized
    fun markLastShown(entry: CachedEntry) {
        val obj = JSONObject().apply {
            put("id", entry.mediaId)
            put("file", entry.imageFile.name)
            put("subtitle", entry.subtitle ?: JSONObject.NULL)
            put("width", entry.width)
            put("height", entry.height)
        }
        runCatching { lastShownPointer().writeText(obj.toString()) }
    }

    @Synchronized
    fun getLastShown(): CachedEntry? {
        val pointer = lastShownPointer()
        if (!pointer.exists()) return null
        return runCatching {
            val obj = JSONObject(pointer.readText())
            val fileName = obj.optString("file")
            val imgFile = File(cacheDir, fileName)
            if (!imgFile.exists() || imgFile.length() <= 0L) return null
            val subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() && it != "null" }
            CachedEntry(
                mediaId = obj.optString("id"),
                imageFile = imgFile,
                subtitle = subtitle,
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0),
            )
        }.getOrNull()
    }

    @Synchronized
    fun totalSizeBytes(): Long {
        return cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    @Synchronized
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun readMetadata(file: File, fallbackId: String): Triple<String, String?, Pair<Int, Int>> {
        if (!file.exists()) return Triple(fallbackId, null, 0 to 0)
        return runCatching {
            val obj = JSONObject(file.readText())
            val id = obj.optString("id", fallbackId)
            val subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() && it != "null" }
            val w = obj.optInt("width", 0)
            val h = obj.optInt("height", 0)
            Triple(id, subtitle, w to h)
        }.getOrDefault(Triple(fallbackId, null, 0 to 0))
    }

    private fun evictIfNeeded(protectFileName: String) {
        val imgFiles = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: return

        var totalBytes = imgFiles.sumOf { it.length() }
        val iter = imgFiles.iterator()
        while (iter.hasNext() && (imgFiles.size > maxEntries || totalBytes > maxBytes)) {
            val oldest = iter.next()
            if (oldest.name == protectFileName) continue
            totalBytes -= oldest.length()
            val metaName = oldest.name.removeSuffix(".img") + ".json"
            oldest.delete()
            File(cacheDir, metaName).delete()
            iter.remove()
        }
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
