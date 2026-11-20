package com.stasisstate.slatetv.connector.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaPickerModelTest {
    private val areas = listOf(HaArea("den", "Den"), HaArea("attic", "Attic"), HaArea("veranda", "Veranda"), HaArea("playroom", "Playroom"))
        .associateBy { it.id }
    private val devices = listOf(
        HaDevice("d_den", "Lights", "den"),
        HaDevice("d_attic", "Lights", "attic"),
        HaDevice("d_attic2", "Reading nook", "attic"),
        HaDevice("d_bell", "Front veranda", "veranda", model = "Video Doorbell"),
        HaDevice("d_verandacam", "Veranda cam", "veranda"),
        HaDevice("d_play", "Toy box", "playroom"),
    ).associateBy { it.id }
    private val registry = HaRegistry(
        areas = areas,
        devices = devices,
        entries = listOf(
            HaRegistryEntry("light.den_ceiling", "Ceiling", "d_den"),
            HaRegistryEntry("light.attic_ceiling", "Ceiling", "d_attic"),
            HaRegistryEntry("light.attic_ceiling_2", "Ceiling", "d_attic2"),
            HaRegistryEntry("light.attic_ceiling_3", "Ceiling", "d_attic2"),
            HaRegistryEntry("switch.attic_identify", "Identify", "d_attic", category = "config"),
            HaRegistryEntry("switch.den_plug", "Plug", "d_den", hidden = true),
            HaRegistryEntry("binary_sensor.front_veranda_visitor", "Visitor", "d_bell", translationKey = "visitor"),
            HaRegistryEntry("binary_sensor.front_veranda_motion", "Motion", "d_bell"),
            HaRegistryEntry("binary_sensor.playroom_bark_sound", null, "d_play"),
            HaRegistryEntry("binary_sensor.playroom_occupancy", null, "d_play"),
            HaRegistryEntry("camera.front_veranda_clear", "Clear", "d_bell"),
            HaRegistryEntry("camera.veranda_cam", null, "d_verandacam"),
            HaRegistryEntry("camera.playroom", null, "d_play"),
        ).associateBy { it.entityId },
    )

    private fun entity(id: String, name: String, state: String = "off", vararg attrs: Pair<String, Any?>) =
        HaEntity(id, state, mapOf("friendly_name" to name) + attrs)

    private val lights = listOf(
        entity("light.den_ceiling", "Ceiling", "on", "brightness" to 128),
        entity("light.attic_ceiling", "Ceiling"),
        entity("light.attic_ceiling_2", "Ceiling"),
        entity("light.attic_ceiling_3", "Ceiling"),
        entity("switch.attic_identify", "Lights Identify"),
        entity("switch.den_plug", "Plug"),
        entity("scene.movie_night", "Movie night", "2026-01-01T00:00:00Z"),
        entity("script.goodnight", "Goodnight"),
        entity("light.yaml_lamp", "Yaml lamp"),
        entity("sensor.ignored", "Temperature", "21"),
    )

    @Test
    fun duplicateNamesAreTellApartByAreaThenDeviceThenId() {
        val items = HaPickerModel.items(lights, registry, HaDomains.HOME_CONTROL).associateBy { it.entityId }
        assertEquals("Ceiling", items.getValue("light.den_ceiling").title)
        assertEquals("Den  ·  Lights", items.getValue("light.den_ceiling").context())
        assertEquals("Attic  ·  Lights", items.getValue("light.attic_ceiling").context())
        // Same name, same area, other device: the device separates them.
        assertEquals("Attic  ·  Reading nook  ·  light.attic_ceiling_2", items.getValue("light.attic_ceiling_2").context())
        // Same name, area and device: only the entity id is left.
        assertEquals("light.attic_ceiling_3", items.getValue("light.attic_ceiling_3").disambiguator)
        assertNull(items.getValue("light.attic_ceiling").disambiguator)
        // Under an area heading the area is not repeated.
        assertEquals("Lights", items.getValue("light.den_ceiling").context(includeArea = false))
        // No area: fall back to what kind of thing it is.
        assertEquals("Scene", items.getValue("scene.movie_night").context())
        assertTrue("sensor.ignored" !in items)
    }

    @Test
    fun sameNameDifferentKindIsQualifiedByKindAndHiddenRivalsDoNotCount() {
        val reg = HaRegistry(
            areas = areas,
            devices = devices,
            entries = mapOf(
                "light.lamp" to HaRegistryEntry("light.lamp", "Lamp", "d_den"),
                "fan.lamp" to HaRegistryEntry("fan.lamp", "Lamp", "d_den"),
                "light.desk" to HaRegistryEntry("light.desk", "Desk", "d_den"),
                "switch.desk" to HaRegistryEntry("switch.desk", "Desk", "d_den", hidden = true),
            ),
        )
        val items = HaPickerModel.items(
            listOf(entity("light.lamp", "Lamp"), entity("fan.lamp", "Lamp"), entity("light.desk", "Desk"), entity("switch.desk", "Desk")),
            reg,
            HaDomains.HOME_CONTROL,
        ).associateBy { it.entityId }
        assertEquals("Light", items.getValue("light.lamp").disambiguator)
        assertEquals("Fan", items.getValue("fan.lamp").disambiguator)
        assertNull(items.getValue("light.desk").disambiguator)
        assertEquals("Switch", items.getValue("switch.desk").disambiguator)
    }

    @Test
    fun deviceNameAlreadyInTitleIsNotRepeated() {
        val visitor = HaPickerModel.describe(entity("binary_sensor.front_veranda_visitor", "Front veranda Visitor"), registry)
        assertNull(visitor.device)
        assertEquals("Veranda", visitor.context())
    }

    @Test
    fun stateLabels() {
        val items = HaPickerModel.items(lights, registry, HaDomains.HOME_CONTROL).associateBy { it.entityId }
        assertEquals("On · 50%", items.getValue("light.den_ceiling").state)
        assertTrue(items.getValue("light.den_ceiling").stateActive)
        assertEquals("Off", items.getValue("light.attic_ceiling").state)
        assertEquals("Scene", items.getValue("scene.movie_night").state)
        assertEquals("Unavailable", HaPickerModel.stateLabel(HaEntity("light.x", HaEntity.STATE_UNAVAILABLE)))
        assertEquals("Recording", HaPickerModel.stateLabel(HaEntity("camera.x", "recording")))
    }

    @Test
    fun hiddenAndConfigEntitiesStayOutUnlessSelectedOrRequested() {
        val items = HaPickerModel.items(lights, registry, HaDomains.HOME_CONTROL)
        val ids = { sections: List<HaPickerSection> -> sections.flatMap { s -> s.items.map { it.entityId } } }
        val default = ids(HaPickerModel.sections(items, HaDomains.HOME_CONTROL))
        assertTrue("switch.attic_identify" !in default)
        assertTrue("switch.den_plug" !in default)
        assertTrue("light.yaml_lamp" in default)
        val all = ids(HaPickerModel.sections(items, HaDomains.HOME_CONTROL, showSecondary = true))
        assertTrue("switch.attic_identify" in all && "switch.den_plug" in all)
        val pinned = HaPickerModel.sections(items, HaDomains.HOME_CONTROL, pinned = listOf("switch.den_plug"))
        assertEquals(listOf("switch.den_plug"), pinned.first().items.map { it.entityId })
    }

    @Test
    fun selectedComeFirstInTheirOrderThenAreasThenAreaLessByDomain() {
        val keep = listOf("script.goodnight", "light.gone", "light.attic_ceiling")
        val items = HaPickerModel.items(lights, registry, HaDomains.HOME_CONTROL, keep = keep)
        val sections = HaPickerModel.sections(items, HaDomains.HOME_CONTROL, pinned = keep)
        assertEquals(
            listOf(HaPickerModel.SECTION_SELECTED, "Attic", "Den", "Lights · no area", "Scenes"),
            sections.map { it.title },
        )
        val selected = sections.first().items
        assertEquals(keep, selected.map { it.entityId })
        // A selection Home Assistant no longer reports is kept so Save doesn't drop it.
        assertTrue(selected[1].missing)
        assertEquals("Gone", selected[1].title)
        assertEquals(listOf("light.attic_ceiling_2", "light.attic_ceiling_3"), sections[1].items.map { it.entityId })
        assertEquals(false, sections[1].showsArea)
        // Every entity appears exactly once.
        val all = sections.flatMap { s -> s.items.map { it.entityId } }
        assertEquals(all.distinct(), all)
    }

    @Test
    fun statesOnlyFallbackGroupsByDomain() {
        val items = HaPickerModel.items(lights, HaRegistry.EMPTY, HaDomains.HOME_CONTROL)
        val sections = HaPickerModel.sections(items, HaDomains.HOME_CONTROL)
        assertEquals(listOf("Lights", "Switches", "Scenes", "Scripts"), sections.map { it.title })
        // Without registry data nothing can be known to be hidden.
        assertTrue(sections.any { s -> s.items.any { it.entityId == "switch.den_plug" } })
        // Identical names with nothing else to go on get their entity id.
        assertEquals("light.attic_ceiling", items.first { it.entityId == "light.attic_ceiling" }.disambiguator)
    }

    @Test
    fun filterMatchesEveryWordAcrossNameAreaAndDevice() {
        val items = HaPickerModel.items(lights, registry, HaDomains.HOME_CONTROL)
        val hits = HaPickerModel.sections(items, HaDomains.HOME_CONTROL, query = " attic  ceil ")
            .flatMap { s -> s.items.map { it.entityId } }
        assertEquals(setOf("light.attic_ceiling", "light.attic_ceiling_2", "light.attic_ceiling_3"), hits.toSet())
        assertTrue(HaPickerModel.sections(items, HaDomains.HOME_CONTROL, query = "nothing like this").isEmpty())
    }

    private val triggers = listOf(
        entity("binary_sensor.playroom_bark_sound", "Playroom bark sound", "off", "device_class" to "sound"),
        entity("binary_sensor.playroom_occupancy", "Playroom occupancy", "off", "device_class" to "occupancy"),
        entity("binary_sensor.front_veranda_motion", "Front veranda Motion", "off", "device_class" to "motion"),
        entity("binary_sensor.front_veranda_visitor", "Front veranda Visitor"),
        entity("binary_sensor.side_gate", "Side gate", "off", "device_class" to "doorbell"),
        entity("event.hallway_buttons", "Hallway buttons", "unknown", "event_types" to listOf("KeyPressed", "KeyHeldDown")),
        // "during" contains "ring"; only whole words count.
        entity("event.washer", "Washer", "unknown", "event_types" to listOf("error_during_cleaning", "water_leak")),
        entity("event.back_door_ding", "Back door", "unknown", "event_types" to listOf("doorbell_pressed")),
        entity("input_button.test_ring", "Test ring", "unknown"),
        entity("input_button.reset_filters", "Reset filters", "unknown"),
    )

    @Test
    fun doorbellCandidatesAreSuggestedAndNoiseIsNot() {
        val byId = triggers.associateBy { it.entityId }
        val items = HaPickerModel.items(triggers, registry, HaDomains.DOORBELL_TRIGGER)
        val sections = HaPickerModel.sections(
            items, HaDomains.DOORBELL_TRIGGER,
            score = { HaPickerModel.doorbellScore(byId.getValue(it.entityId), registry) },
        )
        assertEquals(HaPickerModel.SECTION_SUGGESTED, sections.first().title)
        val suggested = sections.first().items.map { it.entityId }
        // Strong signals lead: doorbell device_class, a doorbell event type, and the press on a doorbell
        // device (no device_class; "doorbell" only in the device model).
        assertEquals(
            setOf("binary_sensor.side_gate", "event.back_door_ding", "binary_sensor.front_veranda_visitor"),
            suggested.take(3).toSet(),
        )
        assertTrue(
            suggested.containsAll(
                listOf("binary_sensor.side_gate", "event.back_door_ding", "input_button.test_ring", "binary_sensor.front_veranda_motion"),
            ),
        )
        assertTrue("binary_sensor.playroom_bark_sound" !in suggested)
        assertTrue("binary_sensor.playroom_occupancy" !in suggested)
        assertTrue("event.hallway_buttons" !in suggested)
        assertTrue("event.washer" !in suggested)
        assertTrue(suggested.indexOf("binary_sensor.front_veranda_motion") > suggested.indexOf("binary_sensor.front_veranda_visitor"))
    }

    @Test
    fun camerasOnTheTriggersDeviceThenAreaComeFirst() {
        val cameras = listOf(
            entity("camera.playroom", "Playroom", "idle"),
            entity("camera.veranda_cam", "Veranda cam", "recording"),
            entity("camera.front_veranda_clear", "Front veranda Clear", "idle"),
        )
        val trigger = "binary_sensor.front_veranda_visitor"
        val scores = cameras.associate { it.entityId to HaPickerModel.cameraScore(it, trigger, registry) }
        assertTrue(scores.getValue("camera.front_veranda_clear") > scores.getValue("camera.veranda_cam"))
        assertTrue(scores.getValue("camera.veranda_cam") >= HaPickerModel.SUGGEST_THRESHOLD)
        assertEquals(0, scores.getValue("camera.playroom"))
        // Without registries, a shared entity-id prefix is the only hint.
        val bare = HaPickerModel.cameraScore(HaEntity("camera.side_gate_main", "idle"), "binary_sensor.side_gate_ring", HaRegistry.EMPTY)
        assertTrue(bare >= HaPickerModel.SUGGEST_THRESHOLD)
        assertEquals(0, HaPickerModel.cameraScore(HaEntity("camera.attic", "idle"), "binary_sensor.side_gate_ring", HaRegistry.EMPTY))
    }
}
