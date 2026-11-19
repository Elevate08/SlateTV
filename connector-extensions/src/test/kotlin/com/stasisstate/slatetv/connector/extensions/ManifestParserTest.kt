package com.stasisstate.slatetv.connector.extensions

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManifestParserTest {
    private val source = "https://example.com/ext/manifest.json".toHttpUrl()
    private val servers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()

    @After fun tearDown() = servers.forEach { it.shutdown() }

    private fun v1(extra: String = "", type: String = "both") = """
        {"schemaVersion":1,"id":"com.example.alpine-feed","name":"Alpine Gallery","description":"Daily photos",
         "version":"1.0.0","author":"Community Author","type":"$type",
         "backgroundEndpoint":"/backgrounds.json","shelfEndpoint":"shelf.json","refreshIntervalSeconds":900 $extra}
    """.trimIndent()

    private fun rejected(body: String, url: okhttp3.HttpUrl = source): String {
        try {
            ManifestParser.parse(body, url)
        } catch (e: ExtensionException) {
            return e.message.orEmpty()
        }
        fail("expected rejection for $body")
        return ""
    }

    @Test fun parsesV1ManifestWithRelativeAndAbsoluteEndpoints() {
        val m = ManifestParser.parse(v1(""","authHeaderName":"X-API-Key""""), source)
        assertEquals("com.example.alpine-feed", m.id)
        assertEquals("Alpine Gallery", m.name)
        assertEquals("Community Author", m.author)
        assertEquals("1.0.0", m.version)
        assertEquals(ExtensionType.BOTH, m.type)
        assertEquals("https://example.com/backgrounds.json", m.backgroundUrl) // absolute-path endpoint
        assertEquals("https://example.com/ext/shelf.json", m.shelfUrl)          // relative to manifest URL
        assertEquals("X-API-Key", m.authHeaderName)
        assertEquals(900, m.refreshIntervalSeconds)
        assertFalse(m.autoDetected)

        val abs = ManifestParser.parse(
            v1().replace("\"/backgrounds.json\"", "\"https://example.com/abs/bg.json\""), source,
        )
        assertEquals("https://example.com/abs/bg.json", abs.backgroundUrl)
    }

    @Test fun typeControlsRequiredEndpoints() {
        val bg = ManifestParser.parse(v1(type = "background"), source)
        assertEquals(ExtensionType.BACKGROUND, bg.type)
        assertNull(bg.shelfUrl)
        val shelf = ManifestParser.parse(v1(type = "shelf"), source)
        assertNull(shelf.backgroundUrl)
        rejected(v1(type = "shelf").replace("\"shelfEndpoint\":\"shelf.json\"", "\"x\":1"))
        rejected(v1(type = "background").replace("\"backgroundEndpoint\":\"/backgrounds.json\"", "\"x\":1"))
        rejected(v1(type = "bogus"))
    }

    @Test fun refreshIntervalClamped() {
        assertEquals(60, ManifestParser.parse(v1().replace("900", "5"), source).refreshIntervalSeconds)
        assertEquals(86_400, ManifestParser.parse(v1().replace("900", "999999999"), source).refreshIntervalSeconds)
        val noRefresh = v1().replace(",\"refreshIntervalSeconds\":900", "")
        assertEquals(900, ManifestParser.parse(noRefresh, source).refreshIntervalSeconds)
    }

    @Test fun crossOriginEndpointsRejected() {
        rejected(v1().replace("\"/backgrounds.json\"", "\"https://evil.example.net/bg.json\""))
        rejected(v1().replace("\"/backgrounds.json\"", "\"http://example.com/bg.json\""))
        rejected(v1().replace("\"/backgrounds.json\"", "\"file:///etc/passwd\""))
    }

    @Test fun badAuthHeaderNameRejected() {
        rejected(v1(""","authHeaderName":"Cookie""""))
        rejected(v1(""","authHeaderName":"X Bad""""))
    }

    @Test fun invalidManifestsRejected() {
        rejected("not json")
        rejected("{}")
        rejected("""{"schemaVersion":2,"id":"a","name":"n","type":"background","backgroundEndpoint":"/b"}""")
        rejected(v1().replace("com.example.alpine-feed", "bad id!"))
        rejected(v1().replace("\"name\":\"Alpine Gallery\"", "\"name\":\"\""))
        rejected("[]")
        rejected("""[{"nothing":"here"},{"url":"javascript:alert(1)"}]""")
        rejected("""{"items":[{"imageUrl":"file:///x.jpg"}]}""")
        rejected("42")
    }

    @Test fun autoWrapsItemsObjectFeed() {
        val url = "https://photos.example.org/feed.json".toHttpUrl()
        val m = ManifestParser.parse("""{"items":[{"imageUrl":"https://photos.example.org/a.jpg"}]}""", url)
        assertTrue(m.autoDetected)
        assertEquals("photos.example.org", m.name)
        assertEquals(ExtensionType.BACKGROUND, m.type)
        assertEquals(url.toString(), m.backgroundUrl)
        assertEquals(url.toString(), m.manifestUrl)
        assertTrue(ManifestParser.validId(m.id))
    }

    @Test fun autoWrapsBareArrayWithUrlAlias() {
        val url = "https://photos.example.org/list.json".toHttpUrl()
        val m = ManifestParser.parse("""[{"url":"https://photos.example.org/a.jpg"}]""", url)
        assertTrue(m.autoDetected)
        assertEquals("photos.example.org", m.name)
        // Different feed URLs on one host get distinct, stable ids.
        val other = ManifestParser.parse("""[{"url":"https://photos.example.org/a.jpg"}]""", "https://photos.example.org/other.json".toHttpUrl())
        assertFalse(m.id == other.id)
        assertEquals(m.id, ManifestParser.parse("""[{"url":"https://photos.example.org/a.jpg"}]""", url).id)
    }

    @Test fun autoWrappedIdValidForLanIpv6Host() {
        val url = "http://[fd12::1]:8080/feed.json".toHttpUrl()
        val m = ManifestParser.parse("""[{"url":"https://x.example/a.jpg"}]""", url)
        assertTrue(m.id, ManifestParser.validId(m.id))
    }

    @Test fun clientRejectsCleartextPublicManifestUrl() {
        try {
            ExtensionClient().fetchManifest("http://example.com/manifest.json", null)
            fail()
        } catch (e: ExtensionException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test fun clientAllowsPrivateLanManifestOverHttp() {
        val server = loopbackServer().also { servers += it }
        server.enqueue(json(v1().replace("/backgrounds.json", "/bg.json")))
        val m = ExtensionClient().fetchManifest(server.base().resolve("/manifest.json").toString(), null)
        assertEquals("http://127.0.0.1:${server.port}/bg.json", m.backgroundUrl)
    }

    @Test fun clientRejectsOversizedManifest() {
        val server = loopbackServer().also { servers += it }
        server.enqueue(json("{\"schemaVersion\":1,\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"))
        try {
            ExtensionClient().fetchManifest(server.base().resolve("/m.json").toString(), null)
            fail()
        } catch (e: ExtensionException) {
            assertTrue(e.message!!.contains("1 MiB"))
        }
    }

    @Test fun clientRejectsChunkedOversizedManifest() {
        val server = loopbackServer().also { servers += it }
        val big = "{\"schemaVersion\":1,\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"
        server.enqueue(json(big).apply { removeHeader("Content-Length"); setChunkedBody(big, 4096) })
        try {
            ExtensionClient().fetchManifest(server.base().resolve("/m.json").toString(), null)
            fail()
        } catch (e: ExtensionException) {
            assertTrue(e.message!!.contains("1 MiB"))
        }
    }

    @Test fun clientSurfacesAuthStatus() {
        val server = loopbackServer().also { servers += it }
        server.enqueue(json("{}", 401))
        try {
            ExtensionClient().fetchManifest(server.base().resolve("/m.json").toString(), null)
            fail()
        } catch (e: ExtensionException) {
            assertEquals(401, e.status)
        }
    }

    @Test fun manifestKeyIsSentOnlyToManifestOrigin() {
        val server = loopbackServer().also { servers += it }
        server.enqueue(json(v1().replace("/backgrounds.json", "/bg.json")))
        ExtensionClient().fetchManifest(server.base().resolve("/m.json").toString(), "secret")
        val req = server.takeRequest()
        assertEquals("Bearer secret", req.getHeader("Authorization"))
        assertEquals("secret", req.getHeader("X-API-Key"))
    }
}
