package dev.slatetv.launcher.wallpaper

import dev.slatetv.plugin.api.BackgroundMedia
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Bounded, atomic LRU disk cache for background images + metadata sidecars.
 *
 * Key NVIDIA Shield reliability guarantees:
 * - Writes image bytes, sidecar JSON, and per-plugin `last_shown` pointers atomically
 *   via temp file + rename so crashes/power-loss never leave partial corrupt files.
 * - Streams downloads directly to disk with an explicit byte cap (never buffers huge originals in RAM).
 * - Protects last-shown frames during LRU eviction and invalidates corrupt entries on decode failure.
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

    private fun lastShownPointerForPlugin(pluginId: String): File {
        val key = sha256Short(pluginId)
        return File(cacheDir, "last_shown_$key.json")
    }

    @Synchronized
    fun put(media: BackgroundMedia, bytes: ByteArray): CachedEntry {
        return putStream(media, bytes.inputStream(), maxBytesPerItem = maxOf(bytes.size.toLong(), 1L))
            ?: throw IllegalStateException("Failed to write cached entry")
    }

    /**
     * Streams [input] into a bounded temp file and atomically commits it to the cache.
     * Returns null if the stream is empty or exceeds [maxBytesPerItem].
     */
    @Synchronized
    fun putStream(
        media: BackgroundMedia,
        input: InputStream,
        maxBytesPerItem: Long = DEFAULT_MAX_ITEM_BYTES,
    ): CachedEntry? {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val imgFile = fileForMediaId(media.id)
        val tmpFile = File(cacheDir, "${imgFile.name}.tmp")
        var totalRead = 0L
        val buffer = ByteArray(16 * 1024)

        val ok = try {
            FileOutputStream(tmpFile).use { out ->
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    totalRead += n
                    if (totalRead > maxBytesPerItem) {
                        return@use false
                    }
                    out.write(buffer, 0, n)
                }
                out.flush()
                totalRead > 0L
            }
        } catch (_: Exception) {
            false
        }

        if (!ok) {
            tmpFile.delete()
            return null
        }

        if (!tmpFile.renameTo(imgFile)) {
            val copied = runCatching {
                tmpFile.copyTo(imgFile, overwrite = true)
                true
            }.getOrDefault(false)
            tmpFile.delete()
            if (!copied) return null
        }

        val now = System.currentTimeMillis()
        imgFile.setLastModified(now)

        val metaJson = JSONObject().apply {
            put("id", media.id)
            put("subtitle", media.subtitle ?: JSONObject.NULL)
            put("width", media.width)
            put("height", media.height)
        }
        writeTextAtomic(metaFileForMediaId(media.id), metaJson.toString())

        evictIfNeeded(protectFileNames = collectProtectedFileNames(imgFile.name))

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
    fun remove(mediaId: String) {
        fileForMediaId(mediaId).delete()
        metaFileForMediaId(mediaId).delete()
    }

    @Synchronized
    fun markLastShown(pluginId: String, entry: CachedEntry) {
        val obj = JSONObject().apply {
            put("id", entry.mediaId)
            put("file", entry.imageFile.name)
            put("subtitle", entry.subtitle ?: JSONObject.NULL)
            put("width", entry.width)
            put("height", entry.height)
        }
        runCatching { writeTextAtomic(lastShownPointerForPlugin(pluginId), obj.toString()) }
    }

    @Synchronized
    fun getLastShown(pluginId: String): CachedEntry? {
        val pointer = lastShownPointerForPlugin(pluginId)
        if (!pointer.exists()) return null
        return runCatching {
            val obj = JSONObject(pointer.readText())
            val fileName = obj.optString("file")
            // Guard against path traversal in tampered pointer files
            if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) {
                return null
            }
            val imgFile = File(cacheDir, fileName)
            if (imgFile.canonicalFile.parentFile != cacheDir.canonicalFile) return null
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

    private fun writeTextAtomic(target: File, text: String) {
        val tmp = File(cacheDir, "${target.name}.tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    private fun collectProtectedFileNames(currentFileName: String): Set<String> {
        val protected = mutableSetOf(currentFileName)
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("last_shown_") && it.name.endsWith(".json") }
            ?.forEach { ptr ->
                runCatching {
                    val f = JSONObject(ptr.readText()).optString("file")
                    if (f.isNotBlank()) protected += f
                }
            }
        return protected
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

    private fun evictIfNeeded(protectFileNames: Set<String>) {
        val imgFiles = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: return

        var totalBytes = imgFiles.sumOf { it.length() }
        val iter = imgFiles.iterator()
        while (iter.hasNext() && (imgFiles.size > maxEntries || totalBytes > maxBytes)) {
            val oldest = iter.next()
            if (oldest.name in protectFileNames) continue
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

    companion object {
        const val DEFAULT_MAX_ITEM_BYTES = 16L * 1024L * 1024L
    }
}
