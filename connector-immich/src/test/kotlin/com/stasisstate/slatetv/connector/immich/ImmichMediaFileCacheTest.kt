package com.stasisstate.slatetv.connector.immich

import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ImmichMediaFileCacheTest {
    private lateinit var server: MockWebServer
    private lateinit var folder: File
    private val http = ImmichApiClient.defaultClient()
    private var keyCounter = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        folder = Files.createTempDirectory("connector-immich-cache").toFile()
    }

    @After
    fun tearDown() {
        // Delayed MockResponses keep a server thread sleeping after the client cancels (test-server artifact).
        runCatching { server.shutdown() }
        folder.deleteRecursively()
    }

    private fun cache(
        maxBytes: Long = MAX_CACHE_BYTES,
        maxFiles: Int = MAX_CACHE_FILES,
        maxFileBytes: Long = MAX_FILE_BYTES,
    ) = ImmichMediaFileCache(folder, maxBytes, maxFiles, maxFileBytes)

    private fun request() = Request.Builder()
        .url(server.url("/api/assets/a/thumbnail?size=preview"))
        .header("x-api-key", "k")
        .build()

    private fun image(size: Int = 1024, type: String = "image/jpeg") = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", type)
        .setBody(Buffer().write(ByteArray(size) { 7 }))

    private fun nextKey() = "origin|preview|asset-${keyCounter++}"

    /** Downloads one image under a fresh key and backdates it so LRU order is deterministic. */
    private fun ImmichMediaFileCache.fetchAged(size: Int, ageIndex: Int, protectedNames: Set<String> = emptySet()): String =
        runBlocking {
            server.enqueue(image(size))
            val file = fetch(nextKey(), http, request(), protectedNames = { protectedNames })!!
            file.setLastModified(1_000_000L + ageIndex * 1_000L)
            file.name
        }

    private fun entryNames() = folder.listFiles().orEmpty().map { it.name }.filterNot { it.startsWith(".") }.toSet()
    private fun tempFiles() = folder.listFiles().orEmpty().filter { it.name.startsWith(".download-") }

    private fun fillCache(): List<File> = (0 until MAX_CACHE_FILES).map { i ->
        File(folder, TofuTls.sha256Hex("filler-$i".toByteArray())).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
            setLastModified(1_000_000L + i * 1_000L)
        }
    }

    @Test
    fun `successful download is published atomically and reused from cache`() = runBlocking {
        server.enqueue(image(2048))
        val cache = cache()
        val file = cache.fetch("k1", http, request())
        assertNotNull(file)
        assertEquals(2048L, file!!.length())
        assertEquals(cache.nameFor("k1"), file.name)
        assertTrue(ImmichMediaFileCache.ENTRY_NAME.matches(file.name))
        assertEquals("k", server.takeRequest().getHeader("x-api-key"))

        val again = cache.fetch("k1", http, request())
        assertEquals(file, again)
        assertEquals("Cache hit must not hit the network", 1, server.requestCount)
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `failed downloads never evict existing cached files`() = runBlocking {
        val cache = cache(maxBytes = 1_000, maxFiles = 2, maxFileBytes = 4_096)
        val first = cache.fetchAged(300, 0)
        val second = cache.fetchAged(300, 1)

        val failures = listOf(
            MockResponse().setResponseCode(500).setHeader("Content-Type", "image/jpeg").setBody("x"),
            MockResponse().setResponseCode(401).setHeader("Content-Type", "image/jpeg").setBody("x"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody(""),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg")
                .setChunkedBody(Buffer().write(ByteArray(5_000)), 512),
        )
        for (failure in failures) {
            server.enqueue(failure)
            try {
                cache.fetch(nextKey(), http, request())
                fail("Expected download failure for $failure")
            } catch (_: IOException) {
            }
        }

        assertEquals(setOf(first, second), entryNames())
        assertTrue("Temp files must be cleaned up", tempFiles().isEmpty())
    }

    @Test
    fun `16 MiB cap rejects oversized images by content-length and by streamed size`() = runBlocking {
        val cache = cache()
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(ByteArray((MAX_FILE_BYTES + 1).toInt()))),
        )
        try {
            cache.fetch("big-1", http, request())
            fail("Content-Length over the cap must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("limit"))
        }

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png")
                .setChunkedBody(Buffer().write(ByteArray((MAX_FILE_BYTES + 1).toInt())), 1 shl 20),
        )
        try {
            cache.fetch("big-2", http, request())
            fail("Streamed body over the cap must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("limit"))
        }

        server.enqueue(image(size = MAX_FILE_BYTES.toInt()))
        assertEquals(MAX_FILE_BYTES, cache.fetch("exact", http, request())!!.length())
        assertEquals(1, entryNames().size)
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `image content type is required`() = runBlocking {
        val cache = cache()
        server.enqueue(image(type = "image/webp"))
        assertNotNull(cache.fetch("webp", http, request()))
        server.enqueue(image(type = "text/plain"))
        try {
            cache.fetch("text", http, request())
            fail("Non-image content type must be rejected")
        } catch (_: IOException) {
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(10))))
        try {
            cache.fetch("untyped", http, request())
            fail("Missing content type must be rejected")
        } catch (_: IOException) {
        }
        assertEquals(1, entryNames().size)
    }

    @Test
    fun `bounded LRU evicts the least recently used unprotected entries by count`() {
        val cache = cache(maxBytes = 10_000, maxFiles = 3)
        val a = cache.fetchAged(100, 0)
        val b = cache.fetchAged(100, 1)
        val c = cache.fetchAged(100, 2)
        val d = cache.fetchAged(100, 3, protectedNames = setOf(a))
        assertEquals("Oldest unprotected (b) is evicted, protected a survives", setOf(a, c, d), entryNames())
        val e = cache.fetchAged(100, 4)
        assertEquals(setOf(c, d, e), entryNames())
    }

    @Test
    fun `byte cap eviction uses the actual downloaded size`() {
        val cache = cache(maxBytes = 1_000, maxFiles = 10)
        val oldest = cache.fetchAged(400, 0)
        val middle = cache.fetchAged(400, 1)
        val small = cache.fetchAged(150, 2)
        assertEquals(setOf(oldest, middle, small), entryNames())

        val big = cache.fetchAged(300, 3, protectedNames = setOf(oldest))
        assertEquals(setOf(oldest, small, big), entryNames())
        assertEquals(850L, folder.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sumOf { it.length() })
    }

    @Test
    fun `cache hit refreshes LRU position`() {
        val cache = cache(maxBytes = 10_000, maxFiles = 2)
        server.enqueue(image(100))
        val aName = runBlocking { cache.fetch("A", http, request())!! }.name
        File(folder, aName).setLastModified(1_000L)
        val b = cache.fetchAged(100, 5)
        runBlocking { cache.fetch("A", http, request()) } // hit → touch
        val c = cache.fetchAged(100, 6)
        assertEquals(setOf(aName, c), entryNames())
        assertFalse(b in entryNames())
    }

    @Test
    fun `default caps are 24 files and 160 MiB`() {
        assertEquals(24, MAX_CACHE_FILES)
        assertEquals(160L * 1024 * 1024, MAX_CACHE_BYTES)
        assertEquals(16L * 1024 * 1024, MAX_FILE_BYTES)
        fillCache()
        val fresh = cache().fetchAged(256, 99)
        assertEquals(MAX_CACHE_FILES, entryNames().size)
        assertTrue(fresh in entryNames())
    }

    @Test
    fun `rejected publish after download does not evict or publish`() = runBlocking {
        val existing = fillCache()
        server.enqueue(image())
        var checked = false
        val file = cache().fetch("rejected", http, request(), shouldPublish = { checked = true; false })
        assertNull(file)
        assertTrue(checked)
        assertEquals(existing.map { it.name }.toSet(), entryNames())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `cancelling while the body streams cancels the download promptly and removes the temp file`() {
        fillCache()
        server.enqueue(image(size = 4 * 1024 * 1024).throttleBody(1024, 5, TimeUnit.SECONDS))
        var shouldPublishCalled = false

        runBlocking {
            val download = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                cache().fetch("slow", http, request(), shouldPublish = { shouldPublishCalled = true; true })
            }
            withTimeout(5_000) {
                while (tempFiles().isEmpty() || tempFiles().first().length() == 0L) delay(10)
            }
            val startNs = System.nanoTime()
            download.cancel()
            withTimeout(2_000) { download.join() }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("Cancellation took $elapsedMs ms", elapsedMs < 1_000L)
            try {
                download.await()
                fail("Cancelled download must not complete")
            } catch (_: CancellationException) {
            }
        }

        assertTrue("Temp file left behind: ${tempFiles()}", tempFiles().isEmpty())
        assertFalse(shouldPublishCalled)
        assertEquals(MAX_CACHE_FILES, entryNames().size)
    }

    @Test
    fun `cancelling before response headers arrive creates no files`() {
        server.enqueue(image().setHeadersDelay(3, TimeUnit.SECONDS))
        runBlocking {
            val download = async(Dispatchers.Default) { cache().fetch("hdr", http, request()) }
            server.takeRequest(5, TimeUnit.SECONDS) ?: fail("Request never reached server")
            val startNs = System.nanoTime()
            download.cancel()
            withTimeout(2_000) { download.join() }
            assertTrue((System.nanoTime() - startNs) / 1_000_000L < 1_500L)
        }
        assertTrue(folder.listFiles().orEmpty().none { !it.name.startsWith(".") })
        assertTrue(tempFiles().isEmpty())
    }

    // ── ImmichMediaSource (BuiltInImmichPlugin core) ──────────────────────────────────

    private class FixedStore(var cfg: ImmichConfig) : ImmichConfigStore {
        override fun load() = cfg
        override fun save(config: ImmichConfig) {
            cfg = config
        }
    }

    private fun assetDispatcher(contentType: String = "image/jpeg") = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.startsWith("/api/search/random") -> MockResponse().setResponseCode(200).setBody(
                """[
                    {"id":"a1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080,"city":"Oslo","country":"Norway","dateTimeOriginal":"2023-05-01T00:00:00Z"}},
                    {"id":"a2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                    {"id":"a3","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}
                ]""",
            )
            request.path!!.contains("/thumbnail") -> MockResponse().setResponseCode(200)
                .setHeader("Content-Type", contentType).setBody(Buffer().write(ByteArray(512) { 3 }))
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun source(store: ImmichConfigStore) =
        ImmichMediaSource(store, ImmichApiClient(), cache(), generation = { 0L })

    private fun configuredStore() = FixedStore(
        ImmichConfig(serverUrl = server.url("").toString().trimEnd('/'), apiKey = "secret-k"),
    )

    @Test
    fun `media source returns private file URIs with no headers and no untrusted TLS`() = runBlocking {
        server.dispatcher = assetDispatcher()
        val source = source(configuredStore())
        assertTrue(source.isConfigured())

        val first = source.fetchNext(PluginRequestContext())!!
        assertEquals("immich:preview:a1", first.id)
        assertTrue(first.imageUri.startsWith("file:///"))
        assertTrue(first.headers.isEmpty())
        assertFalse(first.allowUntrustedTls)
        assertEquals("May 2023 · Oslo, Norway", first.subtitle)
        val file = File(URI(first.imageUri))
        assertTrue(file.isFile)
        assertEquals(folder.canonicalPath, file.parentFile!!.canonicalPath)
        assertFalse(first.imageUri.contains("secret-k"))

        val batch = source.prefetch(PluginRequestContext(lastMediaId = first.id), 5)
        assertEquals(listOf("immich:preview:a2", "immich:preview:a3"), batch.take(2).map { it.id })
        assertTrue(batch.all { it.imageUri.startsWith("file:///") && it.headers.isEmpty() })

        val thumbRequests = (0 until server.requestCount).map { server.takeRequest() }.filter { it.path!!.contains("thumbnail") }
        assertTrue(thumbRequests.isNotEmpty())
        assertTrue(thumbRequests.all { it.getHeader("x-api-key") == "secret-k" })
    }

    @Test
    fun `media source skips assets whose download fails`() = runBlocking {
        server.dispatcher = assetDispatcher(contentType = "text/html")
        val source = source(configuredStore())
        assertNull(source.fetchNext(PluginRequestContext()))
        assertTrue(source.prefetch(PluginRequestContext(), 3).isEmpty())
        assertTrue(entryNames().isEmpty())
    }

    @Test
    fun `media source is unconfigured without server or key and does no network`() = runBlocking {
        val source = source(FixedStore(ImmichConfig(serverUrl = "https://photos.example.com", apiKey = "")))
        assertFalse(source.isConfigured())
        assertNull(source.fetchNext(PluginRequestContext()))
        assertTrue(source.prefetch(PluginRequestContext(), 2).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fileUri uses raw encoded path`() {
        val f = File(folder, "a b")
        assertEquals("file://${f.toURI().rawPath}", ImmichMediaSource.fileUri(f))
        assertTrue(ImmichMediaSource.fileUri(f).contains("a%20b"))
    }
}
