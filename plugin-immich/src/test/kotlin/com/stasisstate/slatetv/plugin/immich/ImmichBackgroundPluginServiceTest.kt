package com.stasisstate.slatetv.plugin.immich

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.ISlateBackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginMediaItem
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class ImmichBackgroundPluginServiceTest {
    private val media = BackgroundMedia(
        id = "immich:preview:asset",
        imageUri = "https://immich.local/api/assets/asset/thumbnail?size=preview",
        headers = mapOf("x-api-key" to "test-key", "Accept" to "image/*"),
        subtitle = "Jan 2026",
        width = 1440,
        height = 810,
    )

    @Test
    fun `binder advertises the external plugin and settings activity`() {
        val binder = ImmichBackgroundPluginService().onBind(null) as ISlateBackgroundPlugin
        assertEquals(PluginContract.API_VERSION, binder.apiVersion)
        assertEquals(IMMICH_PACKAGE, binder.pluginInfo.id)
        assertEquals("$IMMICH_PACKAGE.ImmichSettingsActivity", binder.pluginInfo.settingsActivityClassName)
    }

    @Test
    fun `direct media retains the authentication headers passed into parcel conversion`() {
        val direct = media.forExternalHost { error("Direct media must not be cached") }!!
        assertEquals(media.headers, direct.headers)
        val item = PluginMediaItem.from(direct)
        assertEquals(media.id, item.id)
        assertEquals(media.imageUri, item.imageUri)
        assertEquals(media.subtitle, item.subtitle)
        assertEquals(media.width, item.width)
        assertEquals(media.height, item.height)
        assertFalse(item.allowUntrustedTls)
    }

    @Test
    fun `self-signed media publishes an authenticated download and strips transport credentials`() {
        val localUri = "content://$IMMICH_PACKAGE.media/image"
        val proxied = media.copy(allowUntrustedTls = true).forExternalHost { localUri }!!
        assertEquals(emptyMap<String, String>(), proxied.headers)
        val item = PluginMediaItem.from(proxied)
        assertEquals(localUri, item.imageUri)
        assertEquals(media.id, item.id)
        assertFalse(item.allowUntrustedTls)
    }

    @Test
    fun `failed cache publication does not fall back to direct media`() {
        assertNull(media.copy(allowUntrustedTls = true).forExternalHost { null })
    }

    @Test
    fun `only Slate TV can request media and batch counts are bounded`() {
        val slate = arrayOf("com.stasisstate.slatetv")
        assertEquals(0, allowedMediaCount(5, arrayOf("example.other.app")))
        assertEquals(0, allowedMediaCount(1, emptyArray()))
        assertEquals(0, allowedMediaCount(-1, slate))
        assertEquals(1, allowedMediaCount(1, slate))
        assertEquals(5, allowedMediaCount(Int.MAX_VALUE, slate))
    }

    @Test
    fun `caller trust requires both Slate TV package and matching signature or pinned cert`() {
        val slate = arrayOf("com.stasisstate.slatetv")
        assertFalse(isTrustedCaller(arrayOf("com.evil.app"), signatureMatchesSelf = true))
        assertFalse(isTrustedCaller(slate, signatureMatchesSelf = false))
        assertTrue(isTrustedCaller(slate, signatureMatchesSelf = true))
        assertFalse(
            isTrustedCaller(
                packages = slate,
                signatureMatchesSelf = true,
                pinnedCertSha256 = "abcd",
                callerCertSha256s = setOf("ef01"),
            ),
        )
        assertTrue(
            isTrustedCaller(
                packages = slate,
                signatureMatchesSelf = false,
                pinnedCertSha256 = "ABCD",
                callerCertSha256s = setOf("abcd"),
            ),
        )
    }

    @Test
    fun `ImmichMediaBuffer returns within RPC wait budget and serves warmed queue immediately`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBodyDelay(500, TimeUnit.MILLISECONDS)
                    .setResponseCode(200)
                    .setBody(
                        """[
                            {"id":"p1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                            {"id":"p2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}
                        ]""",
                    ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val api = ImmichApiClient()
                val buffer = ImmichMediaBuffer(api, refillScope = scope, rpcWaitBudgetMs = 150L)
                val store = object : ImmichConfigStore {
                    private var cfg = ImmichConfig(
                        serverUrl = server.url("").toString().trimEnd('/'),
                        apiKey = "test-key",
                    )
                    override fun load() = cfg
                    override fun save(config: ImmichConfig) { cfg = config }
                }

                // Cold call returns empty quickly at 150ms budget instead of blocking past host deadline
                val startNs = System.nanoTime()
                val cold = runBlocking {
                    buffer.take(1, PluginRequestContext(), store) { _, _ -> null }
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                assertTrue("Cold miss returned $cold instead of empty", cold.isEmpty())
                assertTrue("Cold call exceeded budget ($elapsedMs ms)", elapsedMs < 450L)

                // Wait for background refill to complete, then next call hits ready queue immediately
                Thread.sleep(600L)
                val warmStartNs = System.nanoTime()
                val warm = runBlocking {
                    buffer.take(1, PluginRequestContext(), store) { _, _ -> null }
                }
                val warmElapsedMs = (System.nanoTime() - warmStartNs) / 1_000_000L
                assertEquals(1, warm.size)
                assertEquals("immich:preview:p1", warm.first().id)
                assertTrue("Warm hit should be instant ($warmElapsedMs ms)", warmElapsedMs < 100L)

                // Invalidation clears remaining buffered items
                buffer.invalidateAll()
            } finally {
                scope.cancel()
            }
        }
    }

    private val configuredStore = object : ImmichConfigStore {
        override fun load() = ImmichConfig(serverUrl = "https://immich.local", apiKey = "test-key")
        override fun save(config: ImmichConfig) = Unit
    }

    private fun photo(id: String) = media.copy(id = id)

    @Test
    fun `take returns items from a refill that completes inline before the wait subscribes`() {
        // Unconfined runs the refill body synchronously inside ensureRefillLocked (while take holds the
        // reentrant lock), so the refill enqueues, clears itemSignal and finishes before take subscribes.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val buffer = ImmichMediaBuffer(
                ImmichApiClient(),
                refillScope = scope,
                rpcWaitBudgetMs = 2_000L,
                prefetchOverride = { _, _ -> listOf(photo("a"), photo("b")) },
            )
            val startNs = System.nanoTime()
            val result = runBlocking { buffer.take(1, PluginRequestContext(), configuredStore) { _, _ -> null } }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertEquals(listOf("a"), result.map { it.id })
            assertTrue("Ready item must be returned without waiting ($elapsedMs ms)", elapsedMs < 500L)
            // Remaining item stays buffered for the next call
            val next = runBlocking { buffer.take(1, PluginRequestContext(), configuredStore) { _, _ -> null } }
            assertEquals(listOf("b"), next.map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `take wakes when a gated refill enqueues after the caller subscribed`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefetchStarted = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        try {
            val buffer = ImmichMediaBuffer(
                ImmichApiClient(),
                refillScope = scope,
                rpcWaitBudgetMs = 2_000L,
                prefetchOverride = { _, _ ->
                    prefetchStarted.countDown()
                    release.await()
                    listOf(photo("late"))
                },
            )
            val result = runBlocking {
                val pending = async(Dispatchers.Default) {
                    buffer.take(1, PluginRequestContext(), configuredStore) { _, _ -> null }
                }
                assertTrue(prefetchStarted.await(1, TimeUnit.SECONDS))
                delay(50L) // let take subscribe to itemSignal
                release.complete(Unit)
                pending.await()
            }
            assertEquals(listOf("late"), result.map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refill completing between ready check and subscribe cannot strand the item`() {
        // Deterministically drives the old lost wake-up window: the refill is released and given ample
        // time to enqueue + complete + clear itemSignal after take saw an empty queue but before it
        // subscribed. With the check and subscription under one lock, the refill must instead wait for
        // the subscription, so take wakes with the item rather than timing out or returning empty.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        try {
            val buffer = ImmichMediaBuffer(
                ImmichApiClient(),
                refillScope = scope,
                rpcWaitBudgetMs = 2_000L,
                prefetchOverride = { _, _ ->
                    release.await()
                    listOf(photo("raced"))
                },
            )
            var hookCalls = 0
            buffer.beforeSubscribeLocked = {
                if (hookCalls++ == 0) {
                    release.complete(Unit)
                    Thread.sleep(150L)
                }
            }
            val startNs = System.nanoTime()
            val result = runBlocking { buffer.take(1, PluginRequestContext(), configuredStore) { _, _ -> null } }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertEquals(listOf("raced"), result.map { it.id })
            assertTrue("Should wake as soon as the item lands ($elapsedMs ms)", elapsedMs < 1_500L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `take returns empty promptly when the refill finishes without items`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefetchCalls = AtomicInteger()
        try {
            val buffer = ImmichMediaBuffer(
                ImmichApiClient(),
                refillScope = scope,
                rpcWaitBudgetMs = 2_000L,
                prefetchOverride = { _, _ ->
                    prefetchCalls.incrementAndGet()
                    delay(20L)
                    emptyList()
                },
            )
            val startNs = System.nanoTime()
            val result = runBlocking { buffer.take(1, PluginRequestContext(), configuredStore) { _, _ -> null } }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue(result.isEmpty())
            assertTrue("Should not wait out the RPC budget ($elapsedMs ms)", elapsedMs < 1_000L)
            assertEquals(1, prefetchCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stress - no lost wake-up when refills race with take`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val random = java.util.Random(42)
            repeat(2_000) { iteration ->
                val spinNs = random.nextInt(60_000).toLong()
                val source = AtomicInteger(1) // one photo per iteration, handed out by a single refill
                val buffer = ImmichMediaBuffer(
                    ImmichApiClient(),
                    refillScope = scope,
                    rpcWaitBudgetMs = 2_000L,
                    prefetchOverride = { _, _ ->
                        // Vary timing so enqueue/complete lands on both sides of take's subscription.
                        val until = System.nanoTime() + spinNs
                        while (System.nanoTime() < until) Unit
                        if (source.getAndDecrement() > 0) listOf(photo("s$iteration")) else emptyList()
                    },
                )
                val results = runBlocking {
                    (0 until 2).map {
                        async(Dispatchers.Default) {
                            buffer.take(1, PluginRequestContext(), configuredStore) { _, _ -> null }
                        }
                    }.awaitAll()
                }
                val served = results.flatten().map { it.id }
                // Exactly one photo exists per iteration: it must be delivered once, never stranded in the queue.
                assertEquals("Iteration $iteration lost the ready item: $results", listOf("s$iteration"), served)
            }
        } finally {
            scope.cancel()
        }
    }
}
