package com.stasisstate.slatetv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.immich.ImmichApiClient
import com.stasisstate.slatetv.plugin.immich.ImmichApiClient.Companion.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class LoadedBackdrop(
    val mediaId: String,
    val primaryBitmap: Bitmap,
    val blurredBackgroundBitmap: Bitmap?,
    val subtitle: String?,
    val isPortrait: Boolean,
    val smartPortraitBlurApplied: Boolean = blurredBackgroundBitmap != null,
)

/**
 * Memory-safe, streaming, EXIF-aware bitmap loader + fast portrait backdrop blurrer.
 *
 * Designed specifically for 3 GB NVIDIA Shield TV hardware:
 * - Streams HTTP/content inputs directly to disk with a strict 16 MB cap (never buffers raw bytes in RAM).
 * - Decodes ARGB_8888 (smooth 24-bit color with zero sky banding) bounded by BOTH display
 *   dimensions AND a 4.5M pixel ceiling so wide panoramas cannot cause OOM.
 * - Automatically invalidates corrupt/unparseable cache entries so bad responses are never stuck.
 * - Handles all EXIF orientations (1–8 including transpose/transverse) and recycles intermediates.
 */
class BitmapLoader(
    private val context: Context,
    private val diskCache: WallpaperDiskCache,
    private val baseHttp: OkHttpClient = ImmichApiClient.defaultClient(allowUntrustedTls = false),
) {
    @Volatile
    private var cachedUntrustedHost: String? = null

    @Volatile
    private var cachedUntrustedHttp: OkHttpClient? = null

    private fun httpClientFor(urlHost: String, allowUntrustedTls: Boolean): OkHttpClient {
        if (!allowUntrustedTls) return baseHttp
        val normalizedHost = urlHost.lowercase()
        val existing = cachedUntrustedHttp
        if (existing != null && cachedUntrustedHost == normalizedHost) return existing
        val created = ImmichApiClient.defaultClient(
            allowUntrustedTls = true,
            allowedHost = normalizedHost,
            baseClient = baseHttp,
        )
        cachedUntrustedHost = normalizedHost
        cachedUntrustedHttp = created
        return created
    }

    suspend fun load(
        media: BackgroundMedia,
        targetWidth: Int,
        targetHeight: Int,
        smartPortraitBlur: Boolean,
    ): LoadedBackdrop? = withContext(Dispatchers.IO) {
        val uri = media.imageUri
        try {
            when {
                uri.startsWith("ambient://") -> {
                    val bmp = renderAmbientGradient(uri, targetWidth.coerceIn(320, 1280), targetHeight.coerceIn(180, 720))
                    LoadedBackdrop(
                        mediaId = media.id,
                        primaryBitmap = bmp,
                        blurredBackgroundBitmap = null,
                        subtitle = media.subtitle,
                        isPortrait = false,
                        smartPortraitBlurApplied = false,
                    )
                }

                uri.startsWith("http://") || uri.startsWith("https://") -> {
                    val cacheKey = cacheKeyFor(media)
                    val cached = diskCache.get(cacheKey)
                    if (cached != null) {
                        val decoded = decodeFileToBackdrop(cached, targetWidth, targetHeight, smartPortraitBlur)
                        if (decoded != null) return@withContext decoded
                        // Invalidate corrupt cache entry and try one fresh download
                        diskCache.remove(cacheKey)
                    }
                    val fresh = downloadToCache(media.copy(id = cacheKey)) ?: return@withContext null
                    val decoded = decodeFileToBackdrop(fresh, targetWidth, targetHeight, smartPortraitBlur)
                    if (decoded == null) {
                        diskCache.remove(cacheKey)
                    }
                    decoded
                }

                uri.startsWith("file://") -> {
                    val file = runCatching { File(java.net.URI(uri)) }.getOrNull() ?: return@withContext null
                    if (!file.exists()) return@withContext null
                    val entry = WallpaperDiskCache.CachedEntry(
                        mediaId = media.id,
                        imageFile = file,
                        subtitle = media.subtitle,
                        width = media.width,
                        height = media.height,
                    )
                    decodeFileToBackdrop(entry, targetWidth, targetHeight, smartPortraitBlur)
                }

                uri.startsWith("content://") -> {
                    val cacheKey = cacheKeyFor(media)
                    val cached = context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                        diskCache.putStream(media.copy(id = cacheKey), stream, MAX_DOWNLOAD_BYTES)
                    } ?: return@withContext null
                    val decoded = decodeFileToBackdrop(cached, targetWidth, targetHeight, smartPortraitBlur)
                    if (decoded == null) diskCache.remove(cacheKey)
                    decoded
                }

                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun prefetchToDisk(media: BackgroundMedia): Boolean = withContext(Dispatchers.IO) {
        val uri = media.imageUri
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) return@withContext true
        val cacheKey = cacheKeyFor(media)
        if (diskCache.get(cacheKey) != null) return@withContext true
        try {
            downloadToCache(media.copy(id = cacheKey)) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun loadCachedEntry(
        entry: WallpaperDiskCache.CachedEntry,
        targetWidth: Int,
        targetHeight: Int,
        smartPortraitBlur: Boolean,
    ): LoadedBackdrop? = withContext(Dispatchers.IO) {
        val decoded = runCatching {
            decodeFileToBackdrop(entry, targetWidth, targetHeight, smartPortraitBlur)
        }.getOrNull()
        if (decoded == null) {
            diskCache.remove(entry.mediaId)
        }
        decoded
    }

    fun cacheKeyFor(media: BackgroundMedia): String = "${media.id}|${media.imageUri}"

    private suspend fun downloadToCache(media: BackgroundMedia): WallpaperDiskCache.CachedEntry? {
        val parsedUrl = media.imageUri.toHttpUrlOrNull() ?: return null
        val client = httpClientFor(parsedUrl.host, media.allowUntrustedTls)
        val reqBuilder = Request.Builder().url(parsedUrl).get()
        media.headers.forEach { (k, v) ->
            // Sanitize header keys/values to prevent IllegalArgumentException from untrusted plugins
            if (isValidHeader(k, v)) {
                reqBuilder.header(k, v)
            }
        }
        return try {
            client.executeCancellable(reqBuilder.build()).use { resp ->
                if (!resp.isSuccessful) return null
                val contentLength = resp.body?.contentLength() ?: -1L
                if (contentLength > MAX_DOWNLOAD_BYTES) return null
                val stream = resp.body?.byteStream() ?: return null
                diskCache.putStream(media, stream, MAX_DOWNLOAD_BYTES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidHeader(name: String, value: String): Boolean {
        if (name.isBlank() || name.length > 128 || value.length > 4096) return false
        if (name.any { it <= '\u0020' || it >= '\u007f' }) return false
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
        return true
    }

    private fun decodeFileToBackdrop(
        entry: WallpaperDiskCache.CachedEntry,
        targetWidth: Int,
        targetHeight: Int,
        smartPortraitBlur: Boolean,
    ): LoadedBackdrop? {
        val file = entry.imageFile
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val reqW = targetWidth.coerceIn(640, 1920)
        val reqH = targetHeight.coerceIn(360, 1080)

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val oriented = applyExifOrientation(file, raw)
        val isPortrait = oriented.height > oriented.width

        val blurred = if (isPortrait && smartPortraitBlur) {
            createFastBlurredCopy(oriented)
        } else {
            null
        }

        return LoadedBackdrop(
            mediaId = entry.mediaId,
            primaryBitmap = oriented,
            blurredBackgroundBitmap = blurred,
            subtitle = entry.subtitle,
            isPortrait = isPortrait,
            smartPortraitBlurApplied = blurred != null,
        )
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    fun renderAmbientGradient(uri: String, width: Int, height: Int): Bitmap {
        val rawColors = uri.removePrefix("ambient://").split(',').mapNotNull { hex ->
            runCatching { Color.parseColor(hex.trim()) }.getOrNull()
        }
        val colors = when {
            rawColors.size >= 2 -> rawColors.toIntArray()
            else -> intArrayOf(
                Color.parseColor("#0D1520"),
                Color.parseColor("#162436"),
                Color.parseColor("#080B10"),
            )
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                colors,
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bmp
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L
        private const val MAX_DECODED_PIXELS = 4_500_000L // ~18 MB ARGB_8888 ceiling even for panoramas

        internal fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
            var inSampleSize = 1
            if (srcH > reqH || srcW > reqW) {
                val halfH = srcH / 2
                val halfW = srcW / 2
                while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                    inSampleSize *= 2
                }
            }
            // Also enforce total pixel budget for extreme panoramic or tall aspect ratios
            while (((srcW.toLong() / inSampleSize) * (srcH.toLong() / inSampleSize)) > MAX_DECODED_PIXELS) {
                inSampleSize *= 2
            }
            return inSampleSize.coerceAtLeast(1)
        }

        /**
         * Fast 2-pass box blur on a tiny 96px thumbnail for tasteful portrait pillarboxing.
         * Runs in < 1ms and properly recycles intermediate allocations.
         */
        fun createFastBlurredCopy(source: Bitmap): Bitmap {
            val tinyW = 96
            val tinyH = max(54, (tinyW * source.height) / max(1, source.width)).coerceAtMost(128)
            val scaled = Bitmap.createScaledBitmap(source, tinyW, tinyH, true)
            val mutable = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
                scaled
            } else {
                val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
                if (scaled !== source) scaled.recycle()
                copy
            }
            boxBlurInPlace(mutable, radius = 6, passes = 2)
            return mutable
        }

        private fun boxBlurInPlace(bitmap: Bitmap, radius: Int, passes: Int) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            val temp = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            repeat(passes) {
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        var r = 0
                        var g = 0
                        var b = 0
                        var count = 0
                        for (dx in -radius..radius) {
                            val sx = min(w - 1, max(0, x + dx))
                            val c = pixels[row + sx]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                        temp[row + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    }
                }
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        var r = 0
                        var g = 0
                        var b = 0
                        var count = 0
                        for (dy in -radius..radius) {
                            val sy = min(h - 1, max(0, y + dy))
                            val c = temp[sy * w + x]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                        pixels[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    }
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
