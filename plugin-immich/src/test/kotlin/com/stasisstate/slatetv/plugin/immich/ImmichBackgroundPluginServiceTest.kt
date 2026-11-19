package com.stasisstate.slatetv.plugin.immich

import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.ISlateBackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginMediaItem
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CoroutineScope
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
}
