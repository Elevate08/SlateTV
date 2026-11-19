package com.stasisstate.slatetv.connector.immich

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

            val http = ImmichApiClient.defaultClient()
            val req = Request.Builder()
                .url(server.url("/api/assets/1/thumbnail"))
                .header("x-api-key", "super-secret-key")
                .header("Authorization", "Bearer session-token")
                .build()
            http.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
            }

            val originReq = server.takeRequest()
            assertEquals("super-secret-key", originReq.getHeader("x-api-key"))
            assertEquals("Bearer session-token", originReq.getHeader("Authorization"))

            val redirectedReq = otherServer.takeRequest()
            assertEquals(null, redirectedReq.getHeader("x-api-key"))
            assertEquals(null, redirectedReq.getHeader("Authorization"))
        } finally {
            otherServer.shutdown()
        }
    }

    @Test
    fun `HTTP 303 POST redirect becomes GET and strips body representation headers`() {
        server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/api/done"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val http = ImmichApiClient.defaultClient()
        val body = "{}".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(server.url("/api/submit"))
            .header("Content-Encoding", "identity")
            .header("Content-Language", "en")
            .header("Transfer-Encoding", "identity")
            .post(body)
            .build()
        http.newCall(req).execute().use { assertEquals(200, it.code) }

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("application/json; charset=utf-8", first.getHeader("Content-Type"))

        val second = server.takeRequest()
        assertEquals("GET", second.method)
        assertEquals("/api/done", second.path)
        assertEquals(0L, second.bodySize)
        assertNull(second.getHeader("Content-Type"))
        assertNull(second.getHeader("Content-Encoding"))
        assertNull(second.getHeader("Content-Language"))
        assertNull(second.getHeader("Transfer-Encoding"))
        assertNull(second.getHeader("Content-Length"))
    }

    @Test
    fun `LanConfigServer enforces PIN rate limit and single-use consumption`() {
        var submissions = 0
        val lanServer = LanConfigServer(
            initialUrl = "http://192.168.1.10:2283",
            port = 0,
            bindAddressOverride = InetAddress.getByName("127.0.0.1"),
        ) { _, _ ->
            submissions++
        }

        lanServer.use { srv ->
            val startedUrl = srv.start()
            assertFalse("QR URL must not embed the PIN query param", startedUrl.contains("pin="))
            val baseUrl = "http://127.0.0.1:${srv.boundPort}"
            val http = OkHttpClient()

            // Missing Host header is rejected with 400 Bad Request
            java.net.Socket("127.0.0.1", srv.boundPort).use { rawSocket ->
                rawSocket.soTimeout = 2_000
                rawSocket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                rawSocket.getOutputStream().flush()
                val statusLine = rawSocket.getInputStream().bufferedReader().readLine()
                assertTrue("Expected 400 without Host header, got: $statusLine", statusLine.contains("400"))
            }

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
        ) { _, _ -> }
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
    fun `buildAssetImageUrl URL-encodes asset IDs to prevent path traversal`() {
        val url = client.buildAssetImageUrl(baseConfig(), "../admin/secret")
        assertTrue("Expected encoded slash in $url", url.contains("%2F"))
        assertFalse("Raw traversal must not appear in $url", url.contains("/../"))
    }

    // ── Direct sign-in + API key minting ──────────────────────────────────────────────

    private fun serverBase() = server.url("").toString().trimEnd('/')

    @Test
    fun `signInAndCreateApiKey logs in then mints scoped key and logs out`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"tok-abc-999","userEmail":"me@example.com"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"apiKey":{"id":"k1","name":"Slate TV"},"secret":"minted-secret-xyz"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"successful":true}"""))

        val result = client.signInAndCreateApiKey(serverBase(), " me@example.com ", "hunter2-pass")

        assertTrue(result is ImmichSignInResult.Success)
        result as ImmichSignInResult.Success
        assertEquals("minted-secret-xyz", result.apiKey)
        assertEquals(serverBase(), result.serverUrl)

        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/api/auth/login", login.path)
        val loginJson = JSONObject(login.body.readUtf8())
        assertEquals("me@example.com", loginJson.getString("email"))
        assertEquals("hunter2-pass", loginJson.getString("password"))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/api-keys", create.path)
        assertEquals("Bearer tok-abc-999", create.getHeader("Authorization"))
        assertNull(create.getHeader("x-api-key"))
        val createBody = create.body.readUtf8()
        val createJson = JSONObject(createBody)
        assertEquals("Slate TV", createJson.getString("name"))
        val perms = createJson.getJSONArray("permissions")
        assertEquals(listOf("album.read", "asset.read", "asset.view"), (0 until perms.length()).map { perms.getString(it) })
        assertFalse(createBody.contains("hunter2-pass"))

        val logout = server.takeRequest()
        assertEquals("/api/auth/logout", logout.path)
        assertEquals("Bearer tok-abc-999", logout.getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `signInAndCreateApiKey falls back to unscoped key when older server rejects permissions`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"tok-1"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":["property permissions should not exist"]}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"secret":"legacy-secret"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.signInAndCreateApiKey(serverBase(), "a@b.co", "pw")

        assertEquals("legacy-secret", (result as ImmichSignInResult.Success).apiKey)
        server.takeRequest() // login
        val scoped = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(scoped.has("permissions"))
        val legacy = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("Slate TV", legacy.getString("name"))
        assertFalse(legacy.has("permissions"))
    }

    @Test
    fun `signInAndCreateApiKey does not retry non-400 key failures`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"tok-1"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.signInAndCreateApiKey(serverBase(), "a@b.co", "pw")

        assertTrue(result is ImmichSignInResult.Failure)
        assertTrue((result as ImmichSignInResult.Failure).isAuthError)
        assertEquals(3, server.requestCount) // login, one key attempt, logout
    }

    @Test
    fun `signInAndCreateApiKey reports bad credentials on 401 without touching api-keys`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Incorrect email or password"}"""))

        val result = client.signInAndCreateApiKey(serverBase(), "a@b.co", "wrong-pass-77")

        assertTrue(result is ImmichSignInResult.Failure)
        result as ImmichSignInResult.Failure
        assertTrue(result.isAuthError)
        assertFalse(result.message.contains("wrong-pass-77"))
        assertEquals(1, server.requestCount)
        assertEquals("/api/auth/login", server.takeRequest().path)
    }

    @Test
    fun `signInAndCreateApiKey rejects malformed URL and empty credentials without network`() = runTest {
        val bad = client.signInAndCreateApiKey("http://bad host:abc", "a@b.co", "pw")
        assertTrue(bad is ImmichSignInResult.Failure)
        val blankEmail = client.signInAndCreateApiKey(serverBase(), "  ", "pw")
        assertTrue(blankEmail is ImmichSignInResult.Failure)
        val blankUrl = client.signInAndCreateApiKey("", "a@b.co", "pw")
        assertTrue(blankUrl is ImmichSignInResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signInAndCreateApiKey never follows redirects so the password is not replayed`() = runTest {
        val other = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/auth/login").toString()),
            )
            val result = client.signInAndCreateApiKey(serverBase(), "a@b.co", "pw-secret-1")
            assertTrue(result is ImmichSignInResult.Failure)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `signInAndCreateApiKey never leaks password or access token in results or messages`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"tok-LEAKCHECK"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom tok-LEAKCHECK pw-LEAKCHECK"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val failure = client.signInAndCreateApiKey(serverBase(), "a@b.co", "pw-LEAKCHECK")
        val text = failure.toString()
        assertTrue(failure is ImmichSignInResult.Failure)
        assertFalse(text.contains("pw-LEAKCHECK"))
        assertFalse(text.contains("tok-LEAKCHECK"))

        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"tok-LEAKCHECK"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"secret":"minted-key"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val success = client.signInAndCreateApiKey(serverBase(), "a@b.co", "pw-LEAKCHECK")
        assertTrue(success is ImmichSignInResult.Success)
        assertFalse(success.toString().contains("minted-key"))
        assertFalse(success.toString().contains("pw-LEAKCHECK"))
        assertFalse(success.toString().contains("tok-LEAKCHECK"))
    }

    @Test
    fun `signInAndCreateApiKey reports unreachable server`() = runTest {
        val url = serverBase()
        server.shutdown()
        val result = client.signInAndCreateApiKey(url, "a@b.co", "pw")
        assertTrue(result is ImmichSignInResult.Failure)
    }

    // ── URL normalization & discovery filtering ───────────────────────────────────────

    @Test
    fun `normalizeUrl defaults domains to https and preserves explicit scheme and port`() {
        assertEquals("https://photos.example.com", ImmichConfig.normalizeUrl("photos.example.com"))
        assertEquals("https://photos.example.com", ImmichConfig.normalizeUrl(" photos.example.com/ "))
        assertEquals("http://photos.example.com:2283", ImmichConfig.normalizeUrl("photos.example.com:2283"))
        assertEquals("https://photos.example.com:443", ImmichConfig.normalizeUrl("photos.example.com:443"))
        assertEquals("http://photos.example.com", ImmichConfig.normalizeUrl("http://photos.example.com"))
        assertEquals("HTTPS://Photos.Example.com:8443", ImmichConfig.normalizeUrl("HTTPS://Photos.Example.com:8443/"))
        assertEquals("http://10.0.0.5:2283", ImmichConfig.normalizeUrl("10.0.0.5:2283"))
        assertEquals("http://192.168.1.50", ImmichConfig.normalizeUrl("192.168.1.50"))
        assertEquals("http://immich.local", ImmichConfig.normalizeUrl("immich.local"))
        assertEquals("http://nas", ImmichConfig.normalizeUrl("nas"))
        assertEquals("http://localhost:2283", ImmichConfig.normalizeUrl("localhost:2283"))
        assertEquals("", ImmichConfig.normalizeUrl("   "))
    }

    @Test
    fun `discovery accepts immich service type and immich-named http services only`() {
        assertTrue(ImmichServerDiscovery.isImmichService("My Server", "_immich._tcp."))
        assertTrue(ImmichServerDiscovery.isImmichService("Immich on NAS", "_http._tcp."))
        assertTrue(ImmichServerDiscovery.isImmichService("my-IMMICH", "_http._tcp"))
        assertFalse(ImmichServerDiscovery.isImmichService("Printer", "_http._tcp."))
        assertFalse(ImmichServerDiscovery.isImmichService("Immich", "_ssh._tcp."))
        assertFalse(ImmichServerDiscovery.isImmichService(null, null))
        assertEquals("http://10.0.0.5:2283", DiscoveredImmichServer("n", "10.0.0.5", 2283).url)
    }

    @Test
    fun `same-origin redirect keeps the api key`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/api/assets/1/thumbnail?size=preview"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val req = Request.Builder().url(server.url("/api/assets/1/thumb")).header("x-api-key", "k-1").build()
        ImmichApiClient.defaultClient().newCall(req).execute().use { assertEquals(200, it.code) }
        server.takeRequest()
        assertEquals("k-1", server.takeRequest().getHeader("x-api-key"))
    }

    @Test
    fun `listAlbums parses sorts and sends key only in header`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id":"a","albumName":"Zoo","assetCount":3},{"id":"b","albumName":"Beach","assetCount":30},{"id":"","albumName":"bad"}]""",
            ),
        )
        val albums = client.listAlbums(baseConfig())
        assertEquals(listOf("b", "a"), albums.map { it.id })
        assertEquals("Beach", albums.first().name)
        val req = server.takeRequest()
        assertEquals("/api/albums", req.path)
        assertEquals("secret-test-key-123", req.getHeader("x-api-key"))
        assertFalse(req.path!!.contains("secret"))
    }

    @Test
    fun `listAlbums and fetchCandidateAssets return empty without key or valid url`() = runTest {
        assertTrue(client.listAlbums(baseConfig().copy(apiKey = "")).isEmpty())
        assertTrue(client.fetchCandidateAssets(baseConfig().copy(serverUrl = "http://bad host:abc")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `album mode falls back to legacy album endpoint when metadata search is empty`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"assets":{"items":[]}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"alb-1","assets":[{"id":"old-1","type":"IMAGE","exifInfo":{"exifImageWidth":1920,"exifImageHeight":1080}}]}""",
            ),
        )
        val assets = client.fetchCandidateAssets(baseConfig(mode = ImmichSourceMode.ALBUMS, albums = setOf("alb-1")))
        assertEquals(listOf("old-1"), assets.map { it.id })
        assertEquals("/api/search/metadata", server.takeRequest().path)
        assertEquals("/api/albums/alb-1", server.takeRequest().path)
    }

    @Test
    fun `ImmichConfig toString and PinnedCert toString never leak secrets`() {
        val cfg = baseConfig().copy(pinnedCert = PinnedCert("photos.example.com:443", "AAAA"))
        assertFalse(cfg.toString().contains("secret-test-key-123"))
        assertTrue(cfg.toString().contains("***"))
        assertFalse(cfg.pinnedCert.toString().contains("AAAA"))
    }

    // ── TOFU pinning through the API client ───────────────────────────────────────────

    @Test
    fun `pin applies only to https on the pinned origin and is dropped when the origin changes`() {
        val der = byteArrayOf(1, 2, 3)
        val pin = PinnedCert("photos.example.com:443", java.util.Base64.getEncoder().encodeToString(der))
        val cfg = ImmichConfig(serverUrl = "https://photos.example.com", apiKey = "k", pinnedCert = pin)
        assertTrue(cfg.activePinnedCertDer!!.contentEquals(der))
        assertNull(cfg.copy(serverUrl = "http://photos.example.com:443").activePinnedCertDer)
        assertNull(cfg.copy(serverUrl = "https://photos.example.com:8443").activePinnedCertDer)
        assertNull(cfg.copy(serverUrl = "https://evil.example.com").activePinnedCertDer)

        assertEquals(pin, cfg.withServerUrl("https://PHOTOS.example.com/").pinnedCert)
        assertNull(cfg.withServerUrl("https://other.example.com").pinnedCert)
        assertNull(cfg.withServerUrl("").pinnedCert)
        val withAlbums = cfg.copy(selectedAlbumIds = setOf("a"))
        assertEquals(setOf("a"), withAlbums.withServerUrl("https://photos.example.com").selectedAlbumIds)
        assertTrue(withAlbums.withServerUrl("https://other.example.com").selectedAlbumIds.isEmpty())
        assertEquals("photos.example.com", pin.host)
        assertEquals(443, pin.port)
    }

    @Test
    fun `self-signed https server is flagged untrusted then works after pinning the probed cert`() = runTest {
        val cert = TestServerCert.generate("tofu-api")
        val https = MockWebServer().apply { useHttps(cert.serverSocketFactory, false); start() }
        try {
            val base = https.url("").toString().trimEnd('/')
            val unpinned = ImmichConfig(serverUrl = base, apiKey = "k-123")

            val ping = client.ping(base)
            assertTrue(ping is ImmichConnectionResult.Failure)
            assertTrue((ping as ImmichConnectionResult.Failure).isUntrustedCertificate)

            val failed = client.testConnection(unpinned)
            assertTrue(failed is ImmichConnectionResult.Failure)
            failed as ImmichConnectionResult.Failure
            assertTrue(failed.isUntrustedCertificate)
            assertTrue(failed.isTlsError)
            assertEquals(0, https.requestCount)

            val probed = TofuTls.probeLeafCertificate(base)!!
            val pinned = unpinned.copy(pinnedCert = PinnedCert.from(probed))
            assertEquals(probed.sha256Hex, pinned.pinnedCert!!.sha256Hex)

            https.enqueue(MockResponse().setResponseCode(200).setBody("""{"res":"pong"}"""))
            assertTrue(client.ping(base, pinned.pinnedCert) is ImmichConnectionResult.Success)
            https.enqueue(MockResponse().setResponseCode(404))
            val proxied = client.ping(base, pinned.pinnedCert)
            assertTrue("A 404 ping still proves reachability + trust", proxied is ImmichConnectionResult.Success)
            assertFalse((proxied as ImmichConnectionResult.Success).serverRespondedPing)

            https.enqueue(MockResponse().setResponseCode(200).setBody("""{"res":"pong"}"""))
            https.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"a1","albumName":"A","assetCount":1}]"""))
            https.enqueue(MockResponse().setResponseCode(200).setBody("""[]"""))
            val ok = client.testConnection(pinned)
            assertTrue("Expected success, got $ok", ok is ImmichConnectionResult.Success)
            assertEquals(1, (ok as ImmichConnectionResult.Success).albumCount)
            assertSame(client.clientFor(pinned), client.clientFor(pinned))
            assertNotSame(client.clientFor(pinned), client.clientFor(unpinned))
        } finally {
            https.shutdown()
        }
    }

    @Test
    fun `pinned client rejects a server presenting a different certificate`() = runTest {
        val real = TestServerCert.generate("real")
        val impostor = TestServerCert.generate("impostor")
        val https = MockWebServer().apply { useHttps(impostor.serverSocketFactory, false); start() }
        try {
            val base = https.url("").toString().trimEnd('/')
            val origin = ImmichConfig.originOf(base)!!
            val cfg = ImmichConfig(
                serverUrl = base,
                apiKey = "k-123",
                pinnedCert = PinnedCert(origin, TofuTls.encodeBase64(real.der)),
            )
            val result = client.testConnection(cfg)
            assertTrue(result is ImmichConnectionResult.Failure)
            assertTrue((result as ImmichConnectionResult.Failure).isUntrustedCertificate)
            assertEquals(0, https.requestCount)
        } finally {
            https.shutdown()
        }
    }

    @Test
    fun `sign-in to an untrusted certificate never sends the password`() = runTest {
        val cert = TestServerCert.generate("tofu-signin")
        val https = MockWebServer().apply { useHttps(cert.serverSocketFactory, false); start() }
        try {
            val base = https.url("").toString().trimEnd('/')
            val result = client.signInAndCreateApiKey(base, "a@b.co", "pw-NEVER-SENT")
            assertTrue(result is ImmichSignInResult.Failure)
            result as ImmichSignInResult.Failure
            assertTrue(result.isUntrustedCertificate)
            assertFalse(result.toString().contains("pw-NEVER-SENT"))
            assertEquals(0, https.requestCount)

            val pin = PinnedCert.from(TofuTls.probeLeafCertificate(base)!!)
            https.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"tok"}"""))
            https.enqueue(MockResponse().setResponseCode(201).setBody("""{"secret":"pinned-secret"}"""))
            https.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val ok = client.signInAndCreateApiKey(base, "a@b.co", "pw", pin)
            assertEquals("pinned-secret", (ok as ImmichSignInResult.Success).apiKey)
        } finally {
            https.shutdown()
        }
    }

    @Test
    fun `sign-in does not follow same-origin redirects either`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"x"}"""))
        val result = client.signInAndCreateApiKey(serverBase(), "a@b.co", "pw")
        assertTrue(result is ImmichSignInResult.Failure)
        assertTrue((result as ImmichSignInResult.Failure).message.contains("redirected"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `LanConfigServer page offers no TLS trust controls`() {
        LanConfigServer(port = 0, bindAddressOverride = InetAddress.getByName("127.0.0.1")) { _, _ -> }.use { srv ->
            srv.start()
            val body = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${srv.boundPort}/").build())
                .execute().use { it.body!!.string() }
            assertTrue(body.contains("apiKey"))
            assertFalse(body.contains("allowUntrustedTls"))
            assertFalse(body.contains("self-signed", ignoreCase = true))
        }
    }
}
