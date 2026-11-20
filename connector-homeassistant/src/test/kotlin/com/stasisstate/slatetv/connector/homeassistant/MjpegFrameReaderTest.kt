package com.stasisstate.slatetv.connector.homeassistant

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class MjpegFrameReaderTest {
    private fun jpeg(fill: Int, size: Int = 32): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(size) { fill.toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun readsFramesWithContentLength() {
        val a = jpeg(1)
        val b = jpeg(2, 64)
        val buffer = Buffer()
        for (frame in listOf(a, b)) {
            buffer.writeUtf8("--frameboundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n")
            buffer.write(frame)
            buffer.writeUtf8("\r\n")
        }
        val reader = MjpegFrameReader(buffer)
        assertArrayEquals(a, reader.nextFrame())
        assertArrayEquals(b, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test
    fun readsFramesWithoutContentLengthByEndMarker() {
        // 0xFF bytes inside the body that aren't followed by 0xD9 must not end the frame early.
        val a = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 5, 6, 0xFF.toByte(), 0xD9.toByte())
        val buffer = Buffer()
            .writeUtf8("--myboundary\r\nContent-Type: image/jpeg\r\n\r\n").write(a).writeUtf8("\r\n")
            .writeUtf8("--myboundary\r\nContent-Type: image/jpeg\r\n\r\n").write(a).writeUtf8("\r\n")
        val reader = MjpegFrameReader(buffer)
        assertArrayEquals(a, reader.nextFrame())
        assertArrayEquals(a, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test(expected = IOException::class)
    fun rejectsOversizedFrames() {
        val buffer = Buffer().writeUtf8("--b\r\nContent-Type: image/jpeg\r\nContent-Length: 999999999\r\n\r\n")
        MjpegFrameReader(buffer, maxFrameBytes = 1024).nextFrame()
    }
}
