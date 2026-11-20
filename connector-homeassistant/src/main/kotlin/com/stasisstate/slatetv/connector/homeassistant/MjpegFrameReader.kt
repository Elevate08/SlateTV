package com.stasisstate.slatetv.connector.homeassistant

import okio.BufferedSource
import java.io.IOException

/**
 * Splits a `multipart/x-mixed-replace` MJPEG stream into JPEG frames. Parts are located by their
 * `Content-Length` header when present, otherwise by scanning for the JPEG end-of-image marker.
 * Every frame is capped at [maxFrameBytes] so a misbehaving camera can't exhaust memory.
 */
class MjpegFrameReader(
    private val source: BufferedSource,
    private val maxFrameBytes: Long = 4L * 1024 * 1024,
) {
    /** Returns the next JPEG frame, or null at end of stream. */
    fun nextFrame(): ByteArray? {
        while (true) {
            val headers = readPartHeaders() ?: return null
            val length = headers["content-length"]?.trim()?.toLongOrNull()
            val frame = if (length != null && length > 0) {
                if (length > maxFrameBytes) throw IOException("MJPEG frame too large ($length bytes)")
                source.require(length)
                source.readByteArray(length)
            } else {
                readUntilEndOfImage()
            }
            if (frame.size >= 4 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()) return frame
        }
    }

    /**
     * Skips to the next part (past any boundary line) and reads its headers. Returns null at EOF.
     */
    private fun readPartHeaders(): Map<String, String>? {
        val headers = HashMap<String, String>()
        var sawHeader = false
        var linesScanned = 0
        while (true) {
            val line = source.readUtf8Line() ?: return null
            if (++linesScanned > MAX_HEADER_LINES) throw IOException("Malformed MJPEG stream")
            if (line.isBlank()) {
                if (sawHeader) return headers
                continue
            }
            if (line.startsWith("--")) continue // boundary
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1)
                sawHeader = true
            } else if (sawHeader) {
                // Body started without a blank separator line; treat what we have as the headers.
                return headers
            }
        }
    }

    private fun readUntilEndOfImage(): ByteArray {
        // indexOf buffers as needed and returns -1 at EOF or once maxFrameBytes were scanned.
        var from = 0L
        while (true) {
            val index = source.indexOf(0xFF.toByte(), from, maxFrameBytes)
            if (index < 0) {
                if (source.buffer.size >= maxFrameBytes) throw IOException("MJPEG frame too large")
                return source.readByteArray()
            }
            if (!source.request(index + 2)) return source.readByteArray()
            if (source.buffer[index + 1] == 0xD9.toByte()) return source.readByteArray(index + 2)
            from = index + 1
        }
    }

    companion object {
        private const val MAX_HEADER_LINES = 64
    }
}
