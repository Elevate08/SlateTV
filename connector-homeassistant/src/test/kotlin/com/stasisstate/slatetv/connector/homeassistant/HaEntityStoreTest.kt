package com.stasisstate.slatetv.connector.homeassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaEntityStoreTest {
    private fun json(text: String) = JSONObject(text)

    @Test
    fun appliesSnapshotDiffsAndRemovals() {
        val store = HaEntityStore()
        store.apply(json("""{"a":{"light.desk":{"s":"off","a":{"friendly_name":"Desk","supported_color_modes":["brightness"]},"c":"x","lc":1700000000.5}}}"""))
        val desk = store.snapshot.getValue("light.desk")
        assertEquals("off", desk.state)
        assertEquals("Desk", desk.friendlyName)
        assertEquals(1700000000.5, desk.lastUpdated, 0.0)
        assertTrue(desk.supportsBrightness)

        val changes = store.apply(json("""{"c":{"light.desk":{"+":{"s":"on","a":{"brightness":128},"lc":1700000010.0}}}}"""))
        assertEquals(1, changes.size)
        assertEquals("off", changes[0].old?.state)
        val on = store.snapshot.getValue("light.desk")
        assertEquals("on", on.state)
        assertEquals(50, on.brightnessPercent)
        assertEquals("Desk", on.friendlyName)

        store.apply(json("""{"c":{"light.desk":{"+":{"lu":1700000020.0},"-":{"a":["brightness"]}}}}"""))
        val updated = store.snapshot.getValue("light.desk")
        assertEquals("on", updated.state)
        assertNull(updated.attributes["brightness"])
        assertEquals(1700000010.0, updated.lastChanged, 0.0)
        assertEquals(1700000020.0, updated.lastUpdated, 0.0)

        val removed = store.apply(json("""{"r":["light.desk"]}"""))
        assertNull(removed.single().new)
        assertTrue(store.snapshot.isEmpty())
    }

    @Test
    fun diffForUnknownEntityIsIgnored() {
        val store = HaEntityStore()
        assertTrue(store.apply(json("""{"c":{"light.ghost":{"+":{"s":"on"}}}}""")).isEmpty())
    }
}

class DoorbellDetectorTest {
    private fun entity(id: String, state: String, lc: Double = 0.0) = HaEntity(id, state, lastChanged = lc)

    @Test
    fun binarySensorRingsOnlyOnOffToOn() {
        val d = DoorbellDetector()
        val id = "binary_sensor.front_doorbell"
        assertTrue(d.isRing(HaStateChange(entity(id, "off"), entity(id, "on")), 0))
        assertFalse(d.isRing(HaStateChange(entity(id, "on"), entity(id, "off")), 20_000))
        assertFalse(d.isRing(HaStateChange(entity(id, "unavailable"), entity(id, "on")), 40_000))
        assertFalse(d.isRing(HaStateChange(entity(id, "unknown"), entity(id, "on")), 60_000))
        assertFalse(d.isRing(HaStateChange(null, entity(id, "on")), 80_000))
    }

    @Test
    fun repeatsWithinDebounceAreDropped() {
        val d = DoorbellDetector(debounceMillis = 10_000)
        val id = "binary_sensor.door"
        assertTrue(d.isRing(HaStateChange(entity(id, "off"), entity(id, "on")), 1_000))
        assertFalse(d.isRing(HaStateChange(entity(id, "off"), entity(id, "on")), 5_000))
        assertTrue(d.isRing(HaStateChange(entity(id, "off"), entity(id, "on")), 12_000))
    }

    @Test
    fun eventEntityRingsOnFreshTimestampOnly() {
        val d = DoorbellDetector()
        val id = "event.doorbell_ding"
        val pressedIso = "2026-11-20T18:00:00.000+00:00"
        val pressedEpoch = 1_795_197_600.0 // 2026-11-20T18:00:00Z
        // A real press: state timestamp == when HA changed it.
        assertTrue(d.isRing(HaStateChange(entity(id, "unknown"), entity(id, pressedIso, pressedEpoch + 0.2)), 0))
        // HA restart restoring an old press: state is hours older than lastChanged.
        val d2 = DoorbellDetector()
        assertFalse(d2.isRing(HaStateChange(entity(id, "unknown"), entity(id, pressedIso, pressedEpoch + 7200)), 0))
        assertFalse(d2.isRing(HaStateChange(entity(id, "unavailable"), entity(id, pressedIso, pressedEpoch)), 0))
        assertFalse(d2.isRing(HaStateChange(entity(id, pressedIso), entity(id, pressedIso, pressedEpoch)), 0))
    }

    @Test
    fun parsesIsoTimestamps() {
        assertEquals(1_795_197_600.0, DoorbellDetector.parseIsoSeconds("2026-11-20T18:00:00+00:00")!!, 0.001)
        assertNull(DoorbellDetector.parseIsoSeconds("on"))
    }
}
