package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class LiveTvLogoCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = LiveTvLogoCache(File(tmp.root, "connector_livetv_logos"))

    @Test fun downloadsOnceAndReturnsFileUriThatPointsAtTheCachedBytes() = runBlocking {
        val cache = cache()
        var downloads = 0
        val first = cache.publishCached("https://x/logo", "tag", "ns") { downloads++; byteArrayOf(1, 2, 3) }
        val second = cache.publishCached("https://x/logo", "tag", "ns") { downloads++; byteArrayOf(9) }
        assertEquals(first, second)
        assertEquals(1, downloads)
        assertTrue(first!!.startsWith("file:///"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(URI(first)).readBytes())
        assertEquals("connector_livetv_logos", File(URI(first)).parentFile.name)
    }

    @Test fun rejectsEmptyOrOversizedLogos() = runBlocking {
        val cache = cache()
        assertNull(cache.publishCached("a", null, "ns") { ByteArray(0) })
        assertNull(cache.publishCached("b", null, "ns") { ByteArray((MAX_LOGO_BYTES + 1).toInt()) })
        assertNull(cache.publishCached("c", null, "ns") { null })
    }

    @Test fun cacheIsBoundedToFortyEightFiles() = runBlocking {
        val cache = cache()
        repeat(60) { i -> cache.publishCached("https://x/$i", null, "ns") { byteArrayOf(i.toByte(), 1) } }
        assertEquals(48, File(tmp.root, "connector_livetv_logos").listFiles()!!.count { it.name.matches(Regex("[0-9a-f]{32}")) })
    }

    @Test fun systemLogosRefreshDailyAndAreNamespaced() = runBlocking {
        val cache = cache()
        val day = 86_400_000L
        val a = cache.publishSystemLogo("content://logo/1", now = day) { byteArrayOf(1) }
        val b = cache.publishSystemLogo("content://logo/1", now = day + 1000) { byteArrayOf(2) }
        val c = cache.publishSystemLogo("content://logo/1", now = day * 2) { byteArrayOf(3) }
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun fileUriIsRawEncodedFileScheme() {
        val file = File(tmp.root, "a b")
        assertEquals("file://" + file.toURI().rawPath, fileUriString(file))
        assertTrue(fileUriString(file).contains("a%20b"))
    }
}
