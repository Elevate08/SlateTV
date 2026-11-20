package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaRegistryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun parsesDisplayEntitiesAndResolvesAreas() {
        val registry = HaRegistry(
            areas = HaRegistry.parseAreas(JSONArray(AREAS)),
            devices = HaRegistry.parseDevices(JSONArray(DEVICES)),
            entries = HaRegistry.parseEntities(JSONObject(ENTITIES)),
        )
        val ceiling = registry.entry("light.den_ceiling")!!
        assertEquals("Ceiling", ceiling.name)
        assertEquals("dev_dimmer", ceiling.deviceId)
        assertNull(ceiling.category)
        // Device area applies unless the entity overrides it.
        assertEquals("Den", registry.area("light.den_ceiling")?.name)
        assertEquals("Garden", registry.area("light.path_group")?.name)
        // name_by_user wins over the integration's name.
        assertEquals("Wall switch", registry.device("light.den_ceiling")?.name)
        // Category indexes resolve through the response's own map.
        assertEquals("config", registry.entry("button.dimmer_identify")?.category)
        assertEquals("diagnostic", registry.entry("sensor.dimmer_signal")?.category)
        assertTrue(registry.isSecondary("button.dimmer_identify"))
        assertTrue(registry.isSecondary("switch.hidden_plug"))
        assertFalse(registry.isSecondary("light.den_ceiling"))
        // Entities without a registry entry (YAML, no unique_id) are never treated as hidden.
        assertFalse(registry.isSecondary("input_boolean.yaml_only"))
        assertNull(registry.area("input_boolean.yaml_only"))
    }

    @Test
    fun toleratesMissingAndMalformedFields() {
        val entries = HaRegistry.parseEntities(JSONObject("""{"entities":[{"ei":"light.a"},{"en":"no id"},"junk",{"ei":"light.b","ec":7,"di":null}]}"""))
        assertEquals(setOf("light.a", "light.b"), entries.keys)
        assertNull(entries.getValue("light.b").deviceId)
        assertEquals("category:7", entries.getValue("light.b").category)
        assertTrue(HaRegistry.parseEntities(null).isEmpty())
        assertTrue(HaRegistry.parseAreas(null).isEmpty())
        assertTrue(HaRegistry.EMPTY.isEmpty)
    }

    @Test
    fun loaderKeepsWhatSucceededWhenOneCallFails() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(fakeHa(failAreas = true)))
        val registry = HaRegistryLoader.load(HaApiClient(server.url("/").toString(), "good"), "good", timeoutMs = 5_000)
        assertTrue(registry.areas.isEmpty())
        assertEquals("Wall switch", registry.devices["dev_dimmer"]?.name)
        assertEquals("Ceiling", registry.entry("light.den_ceiling")?.name)
    }

    @Test
    fun loaderReturnsEmptyOnRejectedToken() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(fakeHa()))
        val registry = HaRegistryLoader.load(HaApiClient(server.url("/").toString(), "bad"), "bad", timeoutMs = 5_000)
        assertTrue(registry.isEmpty)
    }

    @Test
    fun loaderGivesUpAfterTimeout() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val started = System.currentTimeMillis()
        val registry = HaRegistryLoader.load(HaApiClient(server.url("/").toString(), "good"), "good", timeoutMs = 300)
        assertTrue(registry.isEmpty)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    private fun fakeHa(failAreas: Boolean = false) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2026.11.1"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            val id = message.optInt("id")
            when (message.optString("type")) {
                "auth" -> webSocket.send(
                    if (message.optString("access_token") == "good") """{"type":"auth_ok"}"""
                    else """{"type":"auth_invalid","message":"Invalid access token"}""",
                )
                HaRegistryLoader.ENTITIES -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":$ENTITIES}""")
                HaRegistryLoader.DEVICES -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":$DEVICES}""")
                HaRegistryLoader.AREAS -> webSocket.send(
                    if (failAreas) """{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized","message":"Unauthorized"}}"""
                    else """{"id":$id,"type":"result","success":true,"result":$AREAS}""",
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    companion object {
        // Shapes captured from a real 2026.11 server; names are invented.
        const val AREAS = """[
            {"aliases":[],"area_id":"den","floor_id":null,"icon":null,"labels":[],"name":"Den","picture":null},
            {"aliases":[],"area_id":"garden","floor_id":null,"icon":null,"labels":[],"name":"Garden","picture":null}
        ]"""
        const val DEVICES = """[
            {"id":"dev_dimmer","name":"Smart Dimmer","name_by_user":"Wall switch","area_id":"den","model":"D100","manufacturer":"Acme","disabled_by":null,"entry_type":null},
            {"id":"dev_bell","name":"Gatehouse","name_by_user":null,"area_id":"garden","model":"Video Doorbell Pro","manufacturer":"Acme","disabled_by":null,"entry_type":null}
        ]"""
        const val ENTITIES = """{"entity_categories":{"0":"config","1":"diagnostic"},"entities":[
            {"ei":"light.den_ceiling","pl":"matter","lb":[],"di":"dev_dimmer","np":"device","tk":"light","en":"Ceiling"},
            {"ei":"button.dimmer_identify","pl":"matter","lb":[],"di":"dev_dimmer","ec":0,"en":"Identify"},
            {"ei":"sensor.dimmer_signal","pl":"matter","lb":[],"di":"dev_dimmer","ec":1,"en":"Signal","dp":0},
            {"ei":"switch.hidden_plug","pl":"tplink","lb":[],"hb":true},
            {"ei":"light.path_group","pl":"group","ai":"garden","lb":[],"np":"area","en":"Path lights"},
            {"ei":"binary_sensor.porch_visitor","pl":"acme","lb":[],"di":"dev_bell","tk":"visitor","en":"Visitor"}
        ]}"""
    }
}
