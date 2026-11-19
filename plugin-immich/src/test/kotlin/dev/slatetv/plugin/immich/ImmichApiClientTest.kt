package dev.slatetv.plugin.immich

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

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
    fun `testConnection sends x-api-key header verifies asset view and succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"res":"pong"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[
                    {"id":"alb-1","albumName":"Family","assetCount":42},
                    {"id":"alb-2","albumName":"Trips","assetCount":120}
                ]""",
            ),
        )
        // Random sample asset + thumbnail probe for asset.view scope
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"sample-1","type":"IMAGE"}]"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("fake-image-bytes"))

        val result = client.testConnection(baseConfig())
        assertTrue(result is ImmichConnectionResult.Success)
        val success = result as ImmichConnectionResult.Success
        assertEquals(2, success.albumCount)
        assertTrue(success.serverRespondedPing)

        assertEquals("/api/server/ping", server.takeRequest().path)
        val albumReq = server.takeRequest()
        assertEquals("/api/albums", albumReq.path)
        assertEquals("secret-test-key-123", albumReq.getHeader("x-api-key"))
        assertFalse(albumReq.requestUrl.toString().contains("secret-test-key-123"))
    }

    @Test
    fun `testConnection catches missing asset view permission`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"res":"pong"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[]"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"sample-1","type":"IMAGE"}]"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))

        val result = client.testConnection(baseConfig())
        assertTrue(result is ImmichConnectionResult.Failure)
        val failure = result as ImmichConnectionResult.Failure
        assertTrue(failure.isAuthError)
        assertTrue(failure.message.contains("asset.view"))
    }

    @Test
    fun `testConnection handles malformed URL safely without throwing`() = runTest {
        val result = client.testConnection(ImmichConfig(serverUrl = "http://bad host:abc", apiKey = "k"))
        assertTrue(result is ImmichConnectionResult.Failure)
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
    fun `fetchCandidateAssets sends withExif and visibility on Random and Favorites`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[
                    {
                        "id": "fav-landscape",
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

        val assets = client.fetchCandidateAssets(baseConfig(mode = ImmichSourceMode.FAVORITES, landscapeOnly = true))
        assertEquals(1, assets.size)
        assertEquals("fav-landscape", assets.first().id)
        assertEquals("Oct 2024 · Seattle, Washington", assets.first().formatSubtitle())

        val req = server.takeRequest()
        assertEquals("/api/search/random", req.path)
        val bodyJson = JSONObject(req.body.readUtf8())
        assertTrue(bodyJson.getBoolean("withExif"))
        assertEquals("timeline", bodyJson.getString("visibility"))
        assertTrue(bodyJson.getBoolean("isFavorite"))
    }

    @Test
    fun `fetchCandidateAssets falls back to legacy isArchived when server returns 400 on visibility`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"property visibility should not exist"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id":"legacy-1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}]""",
            ),
        )

        val assets = client.fetchCandidateAssets(baseConfig(mode = ImmichSourceMode.RANDOM_LIBRARY))
        assertEquals(1, assets.size)
        assertEquals("legacy-1", assets[0].id)
        server.takeRequest() // 400
        val secondBody = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(secondBody.getBoolean("isArchived"))
        assertTrue(secondBody.getBoolean("withExif"))
    }

    @Test
    fun `fetchCandidateAssets album mode uses paginated metadata search for Immich v3 compatibility`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "assets": {
                        "items": [
                            {"id":"img-1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                            {"id":"vid-1","type":"VIDEO","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}},
                            {"id":"img-2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}
                        ]
                    }
                }""",
            ),
        )

        val assets = client.fetchCandidateAssets(
            baseConfig(mode = ImmichSourceMode.ALBUMS, albums = setOf("alb-1")),
        )
        assertEquals(2, assets.size)
        assertEquals(setOf("img-1", "img-2"), assets.map { it.id }.toSet())
        val req = server.takeRequest()
        assertEquals("/api/search/metadata", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("alb-1", body.getJSONArray("albumIds").getString(0))
        assertTrue(body.getBoolean("withExif"))
    }

    @Test
    fun `fetchCandidateAssets recent mode advances pages and wraps cleanly`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"assets":{"nextPage":"2","items":[{"id":"r1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}]}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"assets":{"nextPage":null,"items":[{"id":"r2","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}]}}""",
            ),
        )

        val p1 = client.fetchCandidateAssets(baseConfig(mode = ImmichSourceMode.RECENT))
        val p2 = client.fetchCandidateAssets(baseConfig(mode = ImmichSourceMode.RECENT))
        assertEquals("r1", p1.first().id)
        assertEquals("r2", p2.first().id)

        val req1Body = JSONObject(server.takeRequest().body.readUtf8())
        val req2Body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(1, req1Body.getInt("page"))
        assertEquals(2, req2Body.getInt("page"))
    }

    @Test
    fun `origin-bound OkHttp client strips x-api-key on cross-origin redirect`() {
        val otherServer = MockWebServer()
        otherServer.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", otherServer.url("/stolen").toString()),
            )
            otherServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val http = ImmichApiClient.defaultClient(allowUntrustedTls = false)
            val req = Request.Builder()
                .url(server.url("/api/assets/1/thumbnail"))
                .header("x-api-key", "super-secret-key")
                .build()
            http.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
            }

            val originReq = server.takeRequest()
            assertEquals("super-secret-key", originReq.getHeader("x-api-key"))

            val redirectedReq = otherServer.takeRequest()
            assertEquals(null, redirectedReq.getHeader("x-api-key"))
        } finally {
            otherServer.shutdown()
        }
    }

    @Test
    fun `LanConfigServer enforces PIN rate limit and single-use consumption`() {
        var submissions = 0
        val lanServer = LanConfigServer(
            initialUrl = "http://192.168.1.10:2283",
            port = 0,
            bindAddressOverride = InetAddress.getByName("127.0.0.1"),
        ) { _, _, _ ->
            submissions++
        }

        lanServer.use { srv ->
            val startedUrl = srv.start()
            assertTrue(startedUrl.contains("pin=${srv.pin}"))
            val baseUrl = "http://127.0.0.1:${srv.boundPort}"
            val http = OkHttpClient()

            // First valid submission succeeds (200)
            val goodReq = Request.Builder()
                .url("$baseUrl/save")
                .post(
                    okhttp3.FormBody.Builder()
                        .add("pin", srv.pin)
                        .add("serverUrl", "http://192.168.1.50:2283/")
                        .add("apiKey", "my-secret-api-key")
                        .build(),
                )
                .build()
            http.newCall(goodReq).execute().use { assertEquals(200, it.code) }
            assertEquals(1, submissions)

            // Second submission is rejected with 410 Gone (single-use)
            http.newCall(goodReq).execute().use { assertEquals(410, it.code) }
            assertEquals(1, submissions)
        }

        // Separate session: test 5 wrong PINs trigger 429 lockout
        val lockedServer = LanConfigServer(
            port = 0,
            bindAddressOverride = InetAddress.getByName("127.0.0.1"),
        ) { _, _, _ -> }
        lockedServer.use { srv ->
            srv.start()
            val baseUrl = "http://127.0.0.1:${srv.boundPort}"
            val http = OkHttpClient()
            val badReq = Request.Builder()
                .url("$baseUrl/save")
                .post(
                    okhttp3.FormBody.Builder()
                        .add("pin", "000000")
                        .add("serverUrl", "http://192.168.1.50:2283")
                        .add("apiKey", "k")
                        .build(),
                )
                .build()
            repeat(LanConfigServer.MAX_FAILED_PIN_ATTEMPTS - 1) {
                http.newCall(badReq).execute().use { assertEquals(403, it.code) }
            }
            http.newCall(badReq).execute().use { assertEquals(429, it.code) }
        }
    }

    @Test
    fun `ImmichBackgroundPlugin batches requests includes quality in ID and avoids duplicates`() = runTest {
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
            override fun save(config: ImmichConfig) { cfg = config }
        }
        val plugin = ImmichBackgroundPlugin(store, client)
        assertTrue(plugin.isConfigured())

        val first = plugin.fetchNext(dev.slatetv.plugin.api.PluginRequestContext())
        val second = plugin.fetchNext(dev.slatetv.plugin.api.PluginRequestContext(lastMediaId = first?.id))
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("immich:preview:a1", first!!.id)
        assertEquals("immich:preview:a2", second!!.id)
        assertEquals(1, server.requestCount)
    }
}
