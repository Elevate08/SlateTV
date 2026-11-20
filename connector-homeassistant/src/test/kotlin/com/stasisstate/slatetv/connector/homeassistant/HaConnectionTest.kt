package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HaConnectionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    /** A fake HA: auth handshake, then the subscribe result, a snapshot, and one diff. */
    private fun fakeHa(acceptToken: String, received: MutableList<JSONObject>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2026.11.1"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received += message
            when (message.optString("type")) {
                "auth" -> webSocket.send(
                    if (message.optString("access_token") == acceptToken) """{"type":"auth_ok"}"""
                    else """{"type":"auth_invalid","message":"Invalid access token"}""",
                )
                "subscribe_entities" -> {
                    val id = message.getInt("id")
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    webSocket.send("""{"id":$id,"type":"event","event":{"a":{"binary_sensor.door":{"s":"off","a":{},"lc":1.0}}}}""")
                    webSocket.send("""{"id":$id,"type":"event","event":{"c":{"binary_sensor.door":{"+":{"s":"on","lc":2.0}}}}}""")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    @Test
    fun authenticatesSubscribesAndDeliversSnapshotThenEvents() = runBlocking {
        val received = CopyOnWriteArrayList<JSONObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(fakeHa("good", received)))
        val snapshot = CompletableDeferred<JSONObject>()
        val event = CompletableDeferred<JSONObject>()
        val statuses = CopyOnWriteArrayList<HaStatus>()
        val connection = HaConnection(
            api = HaApiClient(server.url("/").toString(), "good"),
            token = "good",
            entityIds = listOf("binary_sensor.door"),
            onStatus = { statuses += it },
            onSnapshot = { snapshot.complete(it) },
            onEvent = { event.complete(it) },
        )
        val job = launch { connection.run() }
        withTimeout(5_000) {
            assertTrue(snapshot.await().has("a"))
            assertTrue(event.await().has("c"))
        }
        job.cancel()
        assertTrue(HaStatus.Connected in statuses)
        val subscribe = received.first { it.optString("type") == "subscribe_entities" }
        assertEquals("binary_sensor.door", subscribe.getJSONArray("entity_ids").getString(0))
        assertEquals("/api/websocket", server.takeRequest().path)
    }

    @Test
    fun rejectedTokenStopsWithAuthFailed() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(fakeHa("good", CopyOnWriteArrayList())))
        val statuses = CopyOnWriteArrayList<HaStatus>()
        val connection = HaConnection(
            api = HaApiClient(server.url("/").toString(), "bad"),
            token = "bad",
            entityIds = listOf("binary_sensor.door"),
            onStatus = { statuses += it },
            onSnapshot = {},
            onEvent = {},
        )
        withTimeout(5_000) { connection.run() }
        assertEquals(HaStatus.AuthFailed, statuses.last())
    }
}
