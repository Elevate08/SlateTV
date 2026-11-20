package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Runs the client against a real Home Assistant server. Skipped unless HA_IT_URL and HA_IT_TOKEN
 * are set. The server needs: `binary_sensor.front_doorbell` (a template on `input_boolean.ring`),
 * `input_button.side_door`, `input_boolean.lamp`, `script.announce_reply`, the `demo` integration
 * (`camera.demo_camera`, `light.bed_light`), and an admin token (firing events needs admin).
 */
class HaLiveServerIT {
    private val url = System.getenv("HA_IT_URL").orEmpty()
    private val token = System.getenv("HA_IT_TOKEN").orEmpty()

    @Test
    fun clientWorksAgainstRealHomeAssistant() = runBlocking {
        assumeTrue("HA_IT_URL/HA_IT_TOKEN not set", url.isNotBlank() && token.isNotBlank())
        val api = HaApiClient(url, token)

        val info = api.validate()
        println("Server: ${info.locationName} ${info.version}")
        val states = api.states().associateBy { it.entityId }
        assertNotNull(states["input_boolean.lamp"])
        assertEquals("Lamp", states["input_boolean.lamp"]!!.friendlyName)

        // Reset the trigger so the ring below is an off→on edge.
        api.callService(ServiceCall("input_boolean", "turn_off", mapOf("entity_id" to "input_boolean.ring")))

        val watched = listOf("binary_sensor.front_doorbell", "input_button.side_door", "input_boolean.lamp", "camera.demo_camera", "light.bed_light")
        val store = HaEntityStore()
        val detector = DoorbellDetector(debounceMillis = 0)
        val rings = Channel<String>(Channel.UNLIMITED)
        val changes = Channel<HaStateChange>(Channel.UNLIMITED)
        val snapshotSeen = kotlinx.coroutines.CompletableDeferred<Unit>()
        val connection = HaConnection(
            api = api,
            token = token,
            entityIds = watched,
            onStatus = { println("status: $it") },
            onSnapshot = {
                store.clear()
                store.apply(it)
                snapshotSeen.complete(Unit)
            },
            onEvent = { event ->
                for (change in store.apply(event)) {
                    changes.trySend(change)
                    val id = change.new?.entityId ?: continue
                    if (id in DOORBELLS && detector.isRing(change, System.currentTimeMillis())) rings.trySend(id)
                }
            },
        )
        val job = launch { connection.run() }
        try {
            withTimeout(10_000) { snapshotSeen.await() }
            assertEquals(watched.toSet(), store.snapshot.keys)
            assertTrue("lc is parsed as epoch seconds", store.snapshot.getValue("input_boolean.lamp").lastChanged > 1_600_000_000)

            // Binary-sensor doorbell: off→on rings once.
            api.callService(ServiceCall("input_boolean", "turn_on", mapOf("entity_id" to "input_boolean.ring")))
            assertEquals("binary_sensor.front_doorbell", withTimeout(10_000) { rings.receive() })
            api.callService(ServiceCall("input_boolean", "turn_off", mapOf("entity_id" to "input_boolean.ring")))

            // Button doorbell: the state becomes a fresh ISO timestamp.
            api.callService(ServiceCall("input_button", "press", mapOf("entity_id" to "input_button.side_door")))
            assertEquals("input_button.side_door", withTimeout(10_000) { rings.receive() })

            // Home tile toggle round-trips through the subscription.
            val before = store.snapshot.getValue("input_boolean.lamp").isOn
            api.callService(HaDomains.tileAction(store.snapshot.getValue("input_boolean.lamp"))!!)
            withTimeout(10_000) {
                while (true) {
                    val change = changes.receive()
                    if (change.new?.entityId == "input_boolean.lamp" && change.new!!.isOn != before) break
                }
            }

            // Brightness: percent maps to brightness_pct and reads back as the same percent.
            api.callService(HaDomains.brightnessAction("light.bed_light", 50))
            withTimeout(10_000) {
                while (true) {
                    val change = changes.receive()
                    if (change.new?.entityId == "light.bed_light" && change.new!!.brightnessPercent == 50) break
                }
            }

            // Quick reply: bound script with variables, plus the reply event.
            val vars = mapOf("message" to "Be right there", "doorbell" to "binary_sensor.front_doorbell", "camera" to "camera.demo_camera", "source" to "slatetv")
            api.callService(HaDomains.replyAction("script.announce_reply", vars))
            api.fireEvent("slatetv_quick_reply", vars)

            // Camera: a still and one MJPEG frame.
            assertTrue(api.cameraSnapshot("camera.demo_camera").size > 1000)
            val call = api.openMjpegStream("camera.demo_camera")
            call.timeout().timeout(15, TimeUnit.SECONDS)
            call.execute().use { response ->
                assertTrue("stream ${response.code}", response.isSuccessful)
                val frame = MjpegFrameReader(response.body!!.source()).nextFrame()
                assertNotNull(frame)
                assertTrue(frame!!.size > 1000)
            }
        } finally {
            job.cancel()
        }
    }

    private companion object {
        val DOORBELLS = setOf("binary_sensor.front_doorbell", "input_button.side_door")
    }
}
