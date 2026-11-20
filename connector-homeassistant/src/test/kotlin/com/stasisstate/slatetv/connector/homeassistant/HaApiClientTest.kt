package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HaApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HaApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = HaApiClient(server.url("/").toString(), "secret-token")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun validateReadsConfigWithBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":"API running."}"""))
        server.enqueue(MockResponse().setBody("""{"location_name":"Home","version":"2026.11.1"}"""))
        val info = api.validate()
        assertEquals("Home", info.locationName)
        assertEquals("2026.11.1", info.version)
        val first = server.takeRequest()
        assertEquals("/api/", first.path)
        assertEquals("Bearer secret-token", first.getHeader("Authorization"))
        assertEquals("/api/config", server.takeRequest().path)
    }

    @Test
    fun rejectedTokenIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.validate()
            fail("expected failure")
        } catch (e: HaApiException) {
            assertEquals(401, e.httpCode)
        }
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/"))
        try {
            api.states()
            fail("expected failure")
        } catch (e: HaApiException) {
            assertEquals(302, e.httpCode)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun callServicePostsJson() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        api.callService(HaDomains.brightnessAction("light.desk", 40))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/services/light/turn_on", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("light.desk", body.getString("entity_id"))
        assertEquals(40, body.getInt("brightness_pct"))
    }

    @Test
    fun scriptRepliesSendNestedVariables() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        api.callService(HaDomains.replyAction("script.say", mapOf("message" to "Hi", "camera" to null)))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("Hi", body.getJSONObject("variables").getString("message"))
    }

    @Test
    fun parsesStates() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """[{"entity_id":"light.a","state":"on","attributes":{"friendly_name":"A","brightness":255},"last_changed":"2026-11-20T18:00:00+00:00"},{"bogus":1}]""",
        ))
        val states = api.states()
        assertEquals(1, states.size)
        assertEquals(100, states[0].brightnessPercent)
        assertTrue(states[0].lastChanged > 0)
    }

    @Test
    fun webSocketUrlUsesWsScheme() {
        assertTrue(api.webSocketRequest().url.toString().endsWith("/api/websocket"))
        assertEquals("ws://ha:8123/api/websocket".replace("ws", "http"),
            HaApiClient("http://ha:8123", "t").webSocketRequest().url.toString())
    }
}
