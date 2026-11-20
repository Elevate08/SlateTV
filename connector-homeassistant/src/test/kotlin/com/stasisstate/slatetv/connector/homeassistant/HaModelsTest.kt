package com.stasisstate.slatetv.connector.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaModelsTest {
    @Test
    fun normalizesServerUrls() {
        assertEquals("http://homeassistant.local:8123", HaConfig.normalizeServerUrl("homeassistant.local:8123"))
        assertEquals("http://10.0.0.5:8123", HaConfig.normalizeServerUrl(" http://10.0.0.5:8123/ "))
        assertEquals("https://ha.example.com", HaConfig.normalizeServerUrl("https://ha.example.com/api/"))
        assertEquals("https://example.com/ha", HaConfig.normalizeServerUrl("https://example.com/ha/"))
        assertEquals("", HaConfig.normalizeServerUrl("ftp://nope"))
        assertEquals("", HaConfig.normalizeServerUrl("http://user:pw@host"))
        assertEquals("", HaConfig.normalizeServerUrl(""))
    }

    @Test
    fun configuredNeedsUrlAndToken() {
        assertFalse(HaConfig(serverUrl = "http://ha:8123").isConfigured)
        assertTrue(HaConfig(serverUrl = "http://ha:8123", token = "t").isConfigured)
    }

    @Test
    fun tileActionsMatchDomains() {
        assertEquals(ServiceCall("light", "toggle", mapOf("entity_id" to "light.a")), HaDomains.tileAction(HaEntity("light.a", "on")))
        assertEquals("turn_on", HaDomains.tileAction(HaEntity("scene.movie", "scening")).service)
        assertEquals("script", HaDomains.tileAction(HaEntity("script.x", "off")).domain)
        assertEquals("cover", HaDomains.tileAction(HaEntity("cover.blinds", "open")).domain)
    }

    @Test
    fun replyActionsPassVariablesToScripts() {
        val vars = mapOf("message" to "Hi")
        val script = HaDomains.replyAction("script.announce", vars)
        assertEquals("turn_on", script.service)
        assertEquals(vars, script.data["variables"])
        assertEquals("press", HaDomains.replyAction("button.chime", vars).service)
        assertEquals("trigger", HaDomains.replyAction("automation.reply", vars).service)
    }

    @Test
    fun brightnessActions() {
        assertEquals("turn_off", HaDomains.brightnessAction("light.a", 0).service)
        assertEquals(100, HaDomains.brightnessAction("light.a", 140).data["brightness_pct"])
    }

    @Test
    fun configCodecsRoundTripAndRejectGarbage() {
        val bells = listOf(DoorbellBinding("binary_sensor.door", "camera.porch"), DoorbellBinding("event.ding"))
        assertEquals(bells, HaConfigStore.decodeDoorbells(HaConfigStore.encodeDoorbells(bells)))
        val replies = listOf(QuickReply("Hi", "script.say"), QuickReply("Bye"))
        assertEquals(replies, HaConfigStore.decodeReplies(HaConfigStore.encodeReplies(replies)))
        assertEquals(listOf("light.a", "switch.b"), HaConfigStore.decodeStrings("""["light.a","switch.b","light.a",""]"""))
        assertTrue(HaConfigStore.decodeDoorbells("not json").isEmpty())
        assertTrue(HaConfigStore.decodeStrings(null).isEmpty())
    }
}
