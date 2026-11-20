package com.stasisstate.slatetv.connector.homeassistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * Streams a Home Assistant camera into [onFrame] (called on the main thread). Prefers the MJPEG
 * proxy stream; if that fails it falls back to polling stills once a second.
 */
internal class CameraLiveFeed(
    private val api: HaApiClient,
    private val entityId: String,
    private val targetWidthPx: Int,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    private var job: Job? = null
    @Volatile private var call: Call? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            onStatus("Connecting to camera…")
            val streamed = runCatching { streamMjpeg() }
            if (streamed.exceptionOrNull() is CancellationException) return@launch
            pollStills()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        call?.cancel()
        call = null
    }

    /** Returns normally only when the stream ended; throws on failure. */
    private suspend fun streamMjpeg() = withContext(Dispatchers.IO) {
        val streamCall = api.openMjpegStream(entityId).also { call = it }
        streamCall.execute().use { response ->
            if (!response.isSuccessful) throw HaApiException("Stream returned ${response.code}", response.code)
            val body = response.body ?: throw HaApiException("Empty stream")
            val reader = MjpegFrameReader(body.source())
            var frames = 0
            while (isActive) {
                val bytes = reader.nextFrame() ?: break
                val bitmap = decode(bytes) ?: continue
                ensureActive()
                withContext(Dispatchers.Main) {
                    if (frames++ == 0) onStatus(null)
                    onFrame(bitmap)
                }
            }
        }
        if (isActive) throw HaApiException("Stream ended")
    }

    private suspend fun pollStills() {
        var failures = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val bitmap = runCatching { withContext(Dispatchers.IO) { decode(api.cameraSnapshot(entityId)) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (bitmap != null) {
                failures = 0
                onStatus(null)
                onFrame(bitmap)
            } else if (++failures >= 3) {
                onStatus("Camera unavailable")
            }
            delay(if (bitmap != null) 1_000L else 3_000L)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private companion object {
        const val MAX_PIXELS = 40L * 1_000_000
    }
}
