package com.stasisstate.slatetv.plugin.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class JellyfinQuickConnectTest {
    private fun MockWebServer.json(body: String, status: Int = 200) {
        enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun MockWebServer.base() = url("/jellyfin/").toString()

    @Test fun initiatePollAndAuthenticate() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Secret":"s3cret","Code":"123456","Authenticated":false}""")
            server.json("""{"Secret":"s3cret","Code":"123456","Authenticated":false}""")
            server.json("""{"Secret":"s3cret","Code":"123456","Authenticated":true}""")
            server.json("""{"User":{"Id":"user-1","Name":"Me"},"AccessToken":"abcdef0123456789","ServerId":"x"}""")

            val session = JellyfinApiClient.initiateQuickConnect(server.base())
            assertEquals(QuickConnectSession("s3cret", "123456"), session)
            val initiate = server.takeRequest()
            assertEquals("POST", initiate.method)
            assertEquals("/jellyfin/QuickConnect/Initiate", initiate.path)
            val header = initiate.getHeader("Authorization").orEmpty()
            assertTrue(header.startsWith("MediaBrowser Client="))
            assertFalse(header.contains("Token="))

            assertFalse(JellyfinApiClient.pollQuickConnect(server.base(), session.secret))
            assertEquals("/jellyfin/QuickConnect/Connect?secret=s3cret", server.takeRequest().path)
            assertTrue(JellyfinApiClient.pollQuickConnect(server.base(), session.secret))
            server.takeRequest()

            val auth = JellyfinApiClient.authenticateWithQuickConnect(server.base(), session.secret)
            assertEquals(QuickConnectAuth("abcdef0123456789", "user-1"), auth)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jellyfin/Users/AuthenticateWithQuickConnect", request.path)
            assertEquals("s3cret", JSONObject(request.body.readUtf8()).getString("Secret"))
        }
    }

    @Test fun awaitPollsUntilApprovedThenSignsIn() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Authenticated":false}""")
            server.json("""{"Authenticated":false}""")
            server.json("""{"Authenticated":true}""")
            server.json("""{"User":{"Id":"u"},"AccessToken":"tok123"}""")
            val auth = JellyfinApiClient.awaitQuickConnect(server.base(), "sec", pollIntervalMs = 5, timeoutMs = 5_000)
            assertEquals(QuickConnectAuth("tok123", "u"), auth)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun awaitReturnsNullWhenCodeIsNeverApproved() = MockWebServer().use { server ->
        runBlocking {
            repeat(20) { server.json("""{"Authenticated":false}""") }
            assertNull(JellyfinApiClient.awaitQuickConnect(server.base(), "sec", pollIntervalMs = 5, timeoutMs = 20))
        }
    }

    @Test fun disabledQuickConnectGivesClearError() = MockWebServer().use { server ->
        runBlocking {
            server.json("Quick connect is not active on this server.", 401)
            val error = runCatching { JellyfinApiClient.initiateQuickConnect(server.base()) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.startsWith("Quick Connect"))
        }
    }

    @Test fun malformedResponsesAndInvalidUrlsAreRejected() = MockWebServer().use { server ->
        runBlocking {
            server.json("""{"Code":"123456"}""")
            assertTrue(runCatching { JellyfinApiClient.initiateQuickConnect(server.base()) }.exceptionOrNull() is IOException)
            server.json("""{"User":{"Id":"u"}}""")
            assertTrue(runCatching { JellyfinApiClient.authenticateWithQuickConnect(server.base(), "s") }.exceptionOrNull() is IOException)
            assertTrue(runCatching { JellyfinApiClient.initiateQuickConnect("not a url") }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
