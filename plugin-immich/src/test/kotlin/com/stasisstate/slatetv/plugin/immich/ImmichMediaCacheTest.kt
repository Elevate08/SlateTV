package com.stasisstate.slatetv.plugin.immich

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ImmichMediaCacheTest {
    private lateinit var server: MockWebServer
    private lateinit var folder: File
    private val grants = CopyOnWriteArrayList<Pair<String, String>>()
    private val revoked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        folder = Files.createTempDirectory("immich-cache-test").toFile()
        grants.clear()
        revoked.clear()
    }

    @After
    fun tearDown() {
        // Delayed MockResponses keep a server thread sleeping after the client cancels; that is
        // a test-server artifact, not a leak in the code under test.
        runCatching { server.shutdown() }
        folder.deleteRecursively()
    }

    private val config get() = ImmichConfig(serverUrl = server.url("").toString().trimEnd('/'), apiKey = "k")

    private fun cache(maxBytes: Long = MAX_CACHE_BYTES, maxFiles: Int = MAX_CACHE_FILES) = ImmichMediaCache(
        folder = folder,
        api = ImmichApiClient(),
        grantRead = { pkg, name -> grants += pkg to name },
        revokeRead = { name -> revoked += name },
        maxCacheBytes = maxBytes,
        maxCacheFiles = maxFiles,
    )

    private fun media() = BackgroundMedia(
        id = "immich:preview:a",
        imageUri = server.url("/api/assets/a/thumbnail").toString(),
        headers = mapOf("x-api-key" to "k"),
        allowUntrustedTls = true,
    )

    private fun image(size: Int = 1024) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(ByteArray(size) { 7 }))

    /** Publishes one image and backdates it so eviction order is deterministic. */
    private fun ImmichMediaCache.publishAged(
        size: Int,
        ageIndex: Int,
        protectedNames: Set<String> = emptySet(),
    ): String = runBlocking {
        server.enqueue(image(size))
        val name = publishName(
            config = config,
            media = media(),
            grantPackages = listOf("pkg.slate"),
            protectedNames = { protectedNames },
        )!!
        File(folder, name).setLastModified(1_000_000L + ageIndex * 1_000L)
        name
    }

    private fun cachedNames() = folder.listFiles().orEmpty().map { it.name }.filterNot { it.startsWith(".") }.toSet()
    private fun tempFiles() = folder.listFiles().orEmpty().filter { it.name.startsWith(".download-") }

    private fun fillCache(): List<File> = (0 until MAX_CACHE_FILES).map { i ->
        File(folder, UUID.randomUUID().toString()).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(1_000_000L + i * 1_000L)
        }
    }

    @Test
    fun `media name is extracted only from Immich media content URIs`() {
        val name = UUID.randomUUID().toString()
        assertEquals(name, immichMediaNameOf("content://$IMMICH_PACKAGE.media/$name"))
        assertNull(immichMediaNameOf("content://other.authority/$name"))
        assertNull(immichMediaNameOf("https://immich.local/api/assets/x/thumbnail"))
        assertNull(immichMediaNameOf("content://$IMMICH_PACKAGE.media/../$name"))
        assertNull(immichMediaNameOf("content://$IMMICH_PACKAGE.media/$name/extra"))
        assertNull(immichMediaNameOf(null))
    }

    @Test
    fun `failed downloads never evict existing cached files`() = runBlocking {
        val cache = cache(maxBytes = 1_000, maxFiles = 2)
        val first = cache.publishAged(300, 0)
        val second = cache.publishAged(300, 1)
        revoked.clear()

        val failures = listOf(
            MockResponse().setResponseCode(500).setHeader("Content-Type", "image/jpeg").setBody("x"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody(""),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg")
                .setChunkedBody(Buffer().write(ByteArray((MAX_FILE_BYTES + 1).toInt())), 1 shl 20),
        )
        for (failure in failures) {
            server.enqueue(failure)
            try {
                cache.publishName(config, media(), listOf("pkg.slate"))
                fail("Expected download failure for $failure")
            } catch (_: IOException) {
            }
        }

        assertEquals(setOf(first, second), cachedNames())
        assertTrue("No URI may be revoked by failed downloads: $revoked", revoked.isEmpty())
        assertTrue("Temp files must be cleaned up", tempFiles().isEmpty())
    }

    @Test
    fun `protected queued and grace files are never evicted or revoked`() {
        val cache = cache(maxBytes = 10_000, maxFiles = 3)
        val queued = cache.publishAged(100, 0)
        val grace = cache.publishAged(100, 1)
        val stale = cache.publishAged(100, 2)
        val protectedNames = setOf(queued, grace)

        val fresh = cache.publishAged(100, 3, protectedNames)

        assertEquals(setOf(queued, grace, fresh), cachedNames())
        assertEquals(listOf(stale), revoked.toList())
    }

    @Test
    fun `cache keeps protected files over its caps instead of breaking live URIs`() {
        val cache = cache(maxBytes = 250, maxFiles = 2)
        val a = cache.publishAged(100, 0)
        val b = cache.publishAged(100, 1)

        val c = cache.publishAged(100, 2, protectedNames = setOf(a, b))

        assertEquals(setOf(a, b, c), cachedNames())
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun `byte cap eviction uses the actual downloaded size of unprotected older files`() {
        val cache = cache(maxBytes = 1_000, maxFiles = 10)
        val oldest = cache.publishAged(400, 0)
        val middle = cache.publishAged(400, 1)
        assertTrue(revoked.isEmpty())

        val small = cache.publishAged(150, 2)
        assertEquals("950 bytes fits under 1000 without eviction", setOf(oldest, middle, small), cachedNames())
        assertTrue(revoked.isEmpty())

        val big = cache.publishAged(300, 3, protectedNames = setOf(oldest))
        assertEquals(setOf(oldest, small, big), cachedNames())
        assertEquals(listOf(middle), revoked.toList())
        assertEquals(850L, folder.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sumOf { it.length() })
    }

    @Test
    fun `default caps hold every queued and grace item at preview sizes`() {
        val cache = cache()
        val names = (0 until 21).map { cache.publishAged(256 * 1024, it) }
        val fresh = cache.publishAged(256 * 1024, 21, protectedNames = names.toSet())
        assertTrue(revoked.isEmpty())
        assertEquals(names.toSet() + fresh, cachedNames())
        assertEquals(listOf("pkg.slate" to fresh), grants.takeLast(1))
    }

    @Test
    fun `buffer protects queued items and recent handoffs for a bounded grace window`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody(
                """[
                    {"id":"p1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                    {"id":"p2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                    {"id":"p3","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}
                ]""",
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var clock = 100_000L
        try {
            val buffer = ImmichMediaBuffer(
                ImmichApiClient(),
                refillScope = scope,
                rpcWaitBudgetMs = 2_000L,
                handoffGraceMs = 30_000L,
                nowMs = { clock },
            )
            val store = object : ImmichConfigStore {
                override fun load() = ImmichConfig(
                    serverUrl = server.url("").toString().trimEnd('/'),
                    apiKey = "k",
                    allowUntrustedTls = true,
                )
                override fun save(config: ImmichConfig) = Unit
            }
            val published = Collections.synchronizedList(mutableListOf<String>())
            val publishLocal: suspend (ImmichConfig, BackgroundMedia, () -> Boolean) -> String? = { _, _, _ ->
                UUID.randomUUID().toString().also { published += it }.let { "content://$IMMICH_PACKAGE.media/$it" }
            }

            val first = runBlocking { buffer.take(1, PluginRequestContext(), store, publishLocal) }
            assertEquals(1, first.size)
            val handedOff = immichMediaNameOf(first.single().imageUri)!!
            val deadline = System.currentTimeMillis() + 3_000L
            while (published.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            Thread.sleep(100)
            val queued = published.toSet() - handedOff
            assertFalse(queued.isEmpty())

            assertEquals(queued + handedOff, buffer.protectedMediaNames())

            clock += 30_001L
            assertEquals("Grace entry expires after the window", queued, buffer.protectedMediaNames())

            buffer.invalidateAll()
            assertTrue("Invalidation drops queued protection", buffer.protectedMediaNames().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `successful publish evicts the oldest entry, publishes, and grants read access`() = runBlocking {
        val existing = fillCache()
        server.enqueue(image())

        val name = cache().publishName(config, media(), listOf("pkg.slate"))

        assertNotNull(name)
        assertTrue(File(folder, name!!).isFile)
        assertEquals(listOf("pkg.slate" to name), grants.toList())
        assertEquals(listOf(existing.first().name), revoked.toList())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `rejected publish after download does not evict, publish, or grant`() = runBlocking {
        val existing = fillCache()
        server.enqueue(image())
        var checked = false

        val name = cache().publishName(config, media(), listOf("pkg.slate")) {
            checked = true
            false
        }

        assertNull(name)
        assertTrue("shouldPublish must be consulted after the download", checked)
        assertEquals(1, server.requestCount)
        assertTrue(grants.isEmpty())
        assertTrue(revoked.isEmpty())
        assertEquals(existing.map { it.name }.toSet(), cachedNames())
    }

    @Test
    fun `cancelling while the body streams cancels the download promptly and removes the temp file`() {
        fillCache()
        server.enqueue(image(size = 4 * 1024 * 1024).throttleBody(1024, 5, TimeUnit.SECONDS))
        var shouldPublishCalled = false

        runBlocking {
            val download = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                cache().publishName(config, media(), listOf("pkg.slate")) {
                    shouldPublishCalled = true
                    true
                }
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
        assertTrue(grants.isEmpty())
        assertTrue(revoked.isEmpty())
        assertEquals(MAX_CACHE_FILES, cachedNames().size)
    }

    @Test
    fun `cancelling before response headers arrive cancels the call and creates no files`() {
        server.enqueue(image().setHeadersDelay(3, TimeUnit.SECONDS))

        runBlocking {
            val download = async(Dispatchers.Default) {
                cache().publishName(config, media(), listOf("pkg.slate"))
            }
            server.takeRequest(5, TimeUnit.SECONDS) ?: fail("Request never reached server")

            val startNs = System.nanoTime()
            download.cancel()
            withTimeout(2_000) { download.join() }
            assertTrue((System.nanoTime() - startNs) / 1_000_000L < 1_500L)
        }

        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertTrue(grants.isEmpty())
    }
}
