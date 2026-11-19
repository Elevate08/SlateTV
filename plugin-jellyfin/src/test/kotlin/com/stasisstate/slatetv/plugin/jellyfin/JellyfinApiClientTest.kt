package com.stasisstate.slatetv.plugin.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class JellyfinApiClientTest {
    private val now = Instant.parse("2026-11-19T12:06:00Z")

    private fun MockWebServer.client() = JellyfinApiClient(JellyfinConfig(url("/jellyfin/").toString(), "test-token"))
    private fun MockWebServer.json(body: String, status: Int = 200) {
        enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test fun publicInfoAndLiveTvAvailability() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"ServerName":"Living Room"}""")
            server.json("""{"IsEnabled":true}""")
            server.json("""{"IsEnabled":false}""")
            server.json("forbidden", 403)
            server.json("missing", 404)
            val client = server.client()
            assertEquals("Living Room", client.serverName())
            assertTrue(client.liveTvEnabled())
            repeat(3) { assertFalse(client.liveTvEnabled()) }
            val request = server.takeRequest()
            assertEquals("/jellyfin/System/Info/Public", request.path)
            assertEquals(client.authorization, request.getHeader("Authorization"))
            repeat(4) { assertEquals("/jellyfin/LiveTv/Info", server.takeRequest().path) }
        }
    }

    @Test fun channelsAndCurrentProgram() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Items":[{"Id":"channel1","Name":"News","Number":"24.1","ImageTags":{"Primary":"tag"},
                "CurrentProgram":{"Id":"p","Name":"News at Noon","EpisodeTitle":"Thursday","Overview":"Headlines",
                "StartDate":"2026-11-19T12:00:00Z","EndDate":"2026-11-19T12:30:00Z","IsLive":true}}]}""")
            val channel = server.client().channels("user", 12, true).single()
            assertEquals("24.1", channel.number)
            assertEquals(server.url("/jellyfin/Items/channel1/Images/Primary?maxWidth=240&quality=90").toString(), channel.imageUri)
            val program = requireNotNull(channel.current)
            assertEquals("p", program.id)
            assertEquals("News at Noon · Thursday", program.title)
            assertEquals("Headlines", program.overview)
            assertTrue(program.isLive)
            assertEquals(20, program.progressPercent(now))
            assertEquals("24m left", program.remainingTime(now))
            assertEquals(0, program.progressPercent(now.minusSeconds(600)))
            assertEquals(100, program.progressPercent(now.plusSeconds(6000)))
            assertEquals(-1, program.copy(end = null).progressPercent(now))
            val url = server.takeRequest().requestUrl!!
            assertEquals("/jellyfin/LiveTv/Channels", url.encodedPath)
            assertEquals("true", url.queryParameter("AddCurrentProgram"))
            assertEquals("true", url.queryParameter("EnableImages"))
            assertEquals("Primary", url.queryParameter("EnableImageTypes"))
            assertEquals("true", url.queryParameter("IsFavorite"))
            assertEquals("12", url.queryParameter("Limit"))
            assertEquals("user", url.queryParameter("UserId"))
            assertFalse(url.toString().contains("test-token"))
        }
    }

    @Test fun upNextSelectsEarliestFutureProgramPerChannel() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Items":[
                {"Id":"late","ChannelId":"a","StartDate":"2026-11-19T14:00:00Z"},
                {"Id":"current","ChannelId":"a","StartDate":"2026-11-19T12:00:00Z"},
                {"Id":"next","ChannelId":"a","StartDate":"2026-11-19T12:30:00Z"},
                {"Id":"other","ChannelId":"b","StartDate":"2026-11-19T13:00:00Z"},
                {"Id":"bad","ChannelId":"c","StartDate":"invalid"}]}""")
            val next = server.client().upNext("user", listOf("a", "b", "c"), now)
            assertEquals(mapOf("a" to "next", "b" to "other"), next.mapValues { it.value.id })
            val url = server.takeRequest().requestUrl!!
            assertEquals("/jellyfin/LiveTv/Programs", url.encodedPath)
            assertEquals("a,b,c", url.queryParameter("ChannelIds"))
            assertEquals("2026-11-19T16:06:00Z", url.queryParameter("MaxStartDate"))
            assertEquals("false", url.queryParameter("HasAired"))
            assertEquals("StartDate", url.queryParameter("SortBy"))
            assertEquals("80", url.queryParameter("Limit"))
        }
    }

    @Test fun crossOriginRedirectStripsAuthAndSameOriginRetainsIt() = MockWebServer().use { origin ->
        MockWebServer().use { destination ->
            runBlocking {
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/local"))
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/remote")))
                destination.json("""{"ServerName":"Redirected"}""")
                val api = origin.client()
                assertEquals("Redirected", api.serverName())
                repeat(2) { assertEquals(api.authorization, origin.takeRequest().getHeader("Authorization")) }
                val request = destination.takeRequest()
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("X-Emby-Token"))
            }
        }
    }

    @Test fun http303PostRedirectBecomesGetAndStripsBodyRepresentationHeaders() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/jellyfin/done"))
        server.json("ok")
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/jellyfin/submit"))
            .header("Authorization", "token")
            .header("Content-Encoding", "identity")
            .header("Content-Language", "en")
            .header("Transfer-Encoding", "identity")
            .post(body).build()
        server.client().client.newCall(request).execute().use { assertEquals(200, it.code) }
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("application/json; charset=utf-8", first.getHeader("Content-Type"))
        val second = server.takeRequest()
        assertEquals("GET", second.method)
        assertEquals("/jellyfin/done", second.path)
        assertEquals("token", second.getHeader("Authorization"))
        assertEquals(0L, second.bodySize)
        for (name in listOf("Content-Type", "Content-Length", "Content-Encoding", "Content-Language", "Transfer-Encoding")) {
            assertNull(name, second.getHeader(name))
        }
    }

    @Test fun malformedUrlsFailBeforeMakingRequests() {
        for (url in listOf("garbage", "file:///tmp/test", "http://", "https://user:pass@localhost", "https://localhost/?api_key=unsafe")) {
            assertThrows(IllegalArgumentException::class.java) { JellyfinApiClient(JellyfinConfig(url, "test-token")) }
        }
    }

    @Test fun autoDetectFallsBackToUserList() = MockWebServer().use { server ->
        runBlocking {
            server.json("unauthorized", 401)
            server.json("""[{"Name":"No ID"},{"Id":"detected"}]""")
            assertEquals("detected", server.client().detectUserId())
            assertEquals("/jellyfin/Users/Me", server.takeRequest().path)
            assertEquals("/jellyfin/Users", server.takeRequest().path)
        }
    }

    @Test fun pairingLocksOnFifthIncorrectPin() {
        val session = PairingSession("123456")
        repeat(4) { assertEquals(403, session.submit("654321", true)) }
        assertEquals(429, session.submit("654321", true))
        assertEquals(429, session.submit("123456", true))
    }

    @Test fun pairingConsumesOneValidSubmission() {
        val session = PairingSession("123456")
        assertEquals(400, session.submit("123456", false))
        assertEquals(200, session.submit("123456", true))
        assertEquals(410, session.submit("123456", true))
    }

    @Test fun pairingHttpEndpointLocksAndDeliversSingleSubmission() {
        val submissions = LinkedBlockingQueue<Triple<String, String, String>>()
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        fun post(server: LanPairingServer, pin: String): Int {
            val form = FormBody.Builder().add("pin", pin)
                .add("server", "http://192.168.1.40:8096")
                .add("key", "pairing-test-token").add("user", "abc").build()
            return http.newCall(Request.Builder().url(server.url + "pair").post(form).build())
                .execute().use { it.code }
        }
        val local = InetAddress.getByName("127.0.0.1")
        LanPairingServer(local) { url, key, user -> submissions.offer(Triple(url, key, user)) }.use { server ->
            val wrongPin = if (server.pin == "000000") "111111" else "000000"
            repeat(4) { assertEquals(403, post(server, wrongPin)) }
            assertEquals(429, post(server, wrongPin))
            assertEquals(429, post(server, server.pin))
            assertTrue(submissions.isEmpty())
        }
        LanPairingServer(local) { url, key, user -> submissions.offer(Triple(url, key, user)) }.use { server ->
            assertEquals(200, post(server, server.pin))
            assertEquals(Triple("http://192.168.1.40:8096/", "pairing-test-token", "abc"),
                submissions.poll(3, TimeUnit.SECONDS))
            assertThrows(java.io.IOException::class.java) { post(server, server.pin) }
            assertTrue(submissions.isEmpty())
        }
    }

    @Test fun testConnectionUsesServerChannelTotal() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Items":[{"Id":"a","Name":"News"}],"TotalRecordCount":230}""")
            assertEquals(230, server.client().channelCount("user"))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("Limit"))
        }
    }

    @Test fun autoDetectAlsoFallsBackOnHttp400() = MockWebServer().use { server ->
        runBlocking {
            server.json("bad request", 400)
            server.json("""[{"Id":"server-key-user"}]""")
            assertEquals("server-key-user", server.client().detectUserId())
        }
    }

    @Test fun upNextScalesLimitProportionallyForLargeChannelBatches() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Items":[]}""")
            val ids = (1..30).map { "ch-$it" }
            server.client().upNext("user", ids, now)
            assertEquals("120", server.takeRequest().requestUrl!!.queryParameter("Limit"))
        }
    }

    @Test fun lanAndTailscaleFilterAllowsPrivateAndMeshAddressesAndBlocksPublicIps() {
        assertTrue(InetAddress.getByName("192.168.1.50").isLanOrTailscale())
        assertTrue(InetAddress.getByName("10.0.0.10").isLanOrTailscale())
        assertTrue(InetAddress.getByName("172.16.4.2").isLanOrTailscale())
        assertTrue(InetAddress.getByName("127.0.0.1").isLanOrTailscale())
        assertTrue(InetAddress.getByName("169.254.10.2").isLanOrTailscale())
        assertTrue(InetAddress.getByName("100.64.0.1").isLanOrTailscale())
        assertTrue(InetAddress.getByName("100.127.255.254").isLanOrTailscale())
        assertTrue(InetAddress.getByName("fd7a:115c:a1e0::1").isLanOrTailscale())

        assertFalse(InetAddress.getByName("8.8.8.8").isLanOrTailscale())
        assertFalse(InetAddress.getByName("100.63.255.255").isLanOrTailscale())
        assertFalse(InetAddress.getByName("100.128.0.1").isLanOrTailscale())
        assertFalse(InetAddress.getByName("2606:4700:4700::1111").isLanOrTailscale())
    }

    @Test fun trustedCallerRequiresMatchingSignatureOrPinnedCertificate() {
        val slate = arrayOf("com.stasisstate.slatetv")
        assertFalse(isTrustedJellyfinCaller(arrayOf("com.other.app"), signatureMatchesSelf = true))
        assertFalse(isTrustedJellyfinCaller(slate, signatureMatchesSelf = false))
        assertTrue(isTrustedJellyfinCaller(slate, signatureMatchesSelf = true))
        assertFalse(isTrustedJellyfinCaller(slate, signatureMatchesSelf = true, pinnedCertSha256 = "aaaa", callerCertSha256s = setOf("bbbb")))
        assertTrue(isTrustedJellyfinCaller(slate, signatureMatchesSelf = false, pinnedCertSha256 = "AAAA", callerCertSha256s = setOf("aaaa")))
    }
}
