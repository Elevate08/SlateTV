package dev.slatetv.plugin.immich

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImmichApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ImmichApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ImmichApiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseConfig(
        mode: ImmichSourceMode = ImmichSourceMode.RANDOM_LIBRARY,
        albums: Set<String> = emptySet(),
        landscapeOnly: Boolean = true,
    ) = ImmichConfig(
        serverUrl = server.url("").toString().trimEnd('/'),
        apiKey = "secret-test-key-123",
        sourceMode = mode,
        selectedAlbumIds = albums,
        landscapeOnly = landscapeOnly,
    )

    @Test
    fun `testConnection sends x-api-key header and succeeds with album count`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"res":"pong"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[
                    {"id":"alb-1","albumName":"Family","assetCount":42},
                    {"id":"alb-2","albumName":"Trips","assetCount":120}
                ]""",
            ),
        )

        val result = client.testConnection(baseConfig())
        assertTrue(result is ImmichConnectionResult.Success)
        val success = result as ImmichConnectionResult.Success
        assertEquals(2, success.albumCount)
        assertTrue(success.serverRespondedPing)

        val pingReq = server.takeRequest()
        assertEquals("/api/server/ping", pingReq.path)

        val albumReq = server.takeRequest()
        assertEquals("/api/albums", albumReq.path)
        assertEquals("secret-test-key-123", albumReq.getHeader("x-api-key"))
        // Ensure API key is NEVER leaked into the URL query string
        assertFalse(albumReq.requestUrl.toString().contains("secret-test-key-123"))
    }

    @Test
    fun `testConnection reports auth error on HTTP 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"res":"pong"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))

        val result = client.testConnection(baseConfig())
        assertTrue(result is ImmichConnectionResult.Failure)
        val failure = result as ImmichConnectionResult.Failure
        assertTrue(failure.isAuthError)
        assertTrue(failure.message.contains("401"))
    }

    @Test
    fun `fetchCandidateAssets handles EXIF rotation when filtering landscape photos`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[
                    {
                        "id": "landscape-normal",
                        "type": "IMAGE",
                        "exifInfo": {
                            "exifImageWidth": 4000,
                            "exifImageHeight": 3000,
                            "orientation": "1",
                            "dateTimeOriginal": "2024-10-15T12:00:00Z",
                            "city": "Seattle",
                            "state": "Washington"
                        }
                    },
                    {
                        "id": "portrait-via-exif-rotation-6",
                        "type": "IMAGE",
                        "exifInfo": {
                            "exifImageWidth": 4000,
                            "exifImageHeight": 3000,
                            "orientation": "6"
                        }
                    },
                    {
                        "id": "video-asset",
                        "type": "VIDEO",
                        "exifInfo": { "exifImageWidth": 3840, "exifImageHeight": 2160 }
                    }
                ]""",
            ),
        )

        val assets = client.fetchCandidateAssets(baseConfig(landscapeOnly = true))
        assertEquals(1, assets.size)
        assertEquals("landscape-normal", assets.first().id)
        assertEquals("Oct 2024 · Seattle, Washington", assets.first().formatSubtitle())

        val req = server.takeRequest()
        assertEquals("/api/search/random", req.path)
        assertEquals("secret-test-key-123", req.getHeader("x-api-key"))
    }

    @Test
    fun `fetchCandidateAssets reads selected albums and shuffles images`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "id": "alb-1",
                    "albumName": "Nature",
                    "assets": [
                        {"id":"img-1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                        {"id":"img-2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}
                    ]
                }""",
            ),
        )

        val assets = client.fetchCandidateAssets(
            baseConfig(mode = ImmichSourceMode.ALBUMS, albums = setOf("alb-1")),
        )
        assertEquals(2, assets.size)
        assertEquals(setOf("img-1", "img-2"), assets.map { it.id }.toSet())
    }

    @Test
    fun `fetchCandidateAssets supports recent metadata endpoint`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "assets": {
                        "items": [
                            {"id":"recent-1","type":"IMAGE","exifInfo":{"exifImageWidth":3000,"exifImageHeight":2000}}
                        ]
                    }
                }""",
            ),
        )

        val assets = client.fetchCandidateAssets(baseConfig(mode = ImmichSourceMode.RECENT))
        assertEquals(1, assets.size)
        assertEquals("recent-1", assets[0].id)
        assertEquals("/api/search/metadata", server.takeRequest().path)
    }

    @Test
    fun `buildAssetImageUrl uses configured quality and keeps API key in header only`() {
        val cfg = baseConfig().copy(imageQuality = ImmichImageQuality.PREVIEW)
        val url = client.buildAssetImageUrl(cfg, "asset-42")
        assertTrue(url.endsWith("/api/assets/asset-42/thumbnail?size=preview"))
        assertFalse(url.contains("secret-test-key-123"))

        val headers = client.buildAuthHeaders(cfg)
        assertEquals("secret-test-key-123", headers["x-api-key"])
    }

    @Test
    fun `LanConfigServer enforces pairing PIN and delivers credentials`() {
        var receivedUrl: String? = null
        var receivedKey: String? = null
        var receivedUntrusted = false

        val lanServer = LanConfigServer(
            initialUrl = "http://192.168.1.10:2283",
            port = 0,
        ) { url, key, untrusted ->
            receivedUrl = url
            receivedKey = key
            receivedUntrusted = untrusted
        }

        lanServer.use { srv ->
            val startedUrl = srv.start()
            assertTrue(startedUrl.contains("pin=${srv.pin}"))
            val baseUrl = "http://127.0.0.1:${srv.boundPort}"
            val http = OkHttpClient()

            // Reject invalid PIN with 403
            val badReq = Request.Builder()
                .url("$baseUrl/save")
                .post(
                    okhttp3.FormBody.Builder()
                        .add("pin", "000000")
                        .add("serverUrl", "http://192.168.1.50:2283")
                        .add("apiKey", "my-key")
                        .build(),
                )
                .build()
            http.newCall(badReq).execute().use { resp ->
                assertEquals(403, resp.code)
            }
            assertEquals(null, receivedUrl)

            // Accept valid PIN with 200
            val goodReq = Request.Builder()
                .url("$baseUrl/save")
                .post(
                    okhttp3.FormBody.Builder()
                        .add("pin", srv.pin)
                        .add("serverUrl", "http://192.168.1.50:2283/")
                        .add("apiKey", "my-secret-api-key")
                        .add("allowUntrustedTls", "on")
                        .build(),
                )
                .build()
            http.newCall(goodReq).execute().use { resp ->
                assertEquals(200, resp.code)
            }
            assertEquals("http://192.168.1.50:2283", receivedUrl)
            assertEquals("my-secret-api-key", receivedKey)
            assertTrue(receivedUntrusted)
        }
    }

    @Test
    fun `ImmichBackgroundPlugin batches requests and avoids immediate duplicate`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[
                    {"id":"a1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                    {"id":"a2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}
                ]""",
            ),
        )
        val store = object : ImmichConfigStore {
            var cfg = baseConfig()
            override fun load() = cfg
            override fun save(config: ImmichConfig) {
                cfg = config
            }
        }
        val plugin = ImmichBackgroundPlugin(store, client)
        assertTrue(plugin.isConfigured())

        val first = plugin.fetchNext(dev.slatetv.plugin.api.PluginRequestContext())
        val second = plugin.fetchNext(dev.slatetv.plugin.api.PluginRequestContext(lastMediaId = "a1"))
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("immich:a1", first!!.id)
        assertEquals("immich:a2", second!!.id)
        // Both items came from a single network batch request
        assertEquals(1, server.requestCount)
    }
}
