package com.stasisstate.slatetv.shelf

import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfItem
import com.stasisstate.slatetv.plugin.api.ShelfPayload
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfHostingTest {
    @Test
    fun registryDeduplicatesIdsAndRemovesUninstalledServices() {
        val first = Provider("first")
        val duplicate = Provider("first")
        val second = Provider("second")
        var installed = listOf(first, duplicate, second)
        val registry = ShelfPluginRegistry { installed }
        assertEquals(listOf(first, second), registry.refresh())
        installed = listOf(second)
        assertEquals(listOf(second), registry.refresh())
        assertEquals(listOf(second), registry.plugins)
    }

    @Test
    fun sanitizationBoundsItemsAndRejectsUnsafeFields() {
        val provider = Provider("tv")
        val cards = (0..30).map {
            ShelfItem(
                id = "item-$it",
                title = "a".repeat(200),
                subtitle = "b".repeat(200),
                imageUri = "file:///data/private/logo",
                progressPercent = 101,
                launchUri = "intent://destination",
                launchActivityClassName = "other/package",
            )
        }
        val clean = sanitizePayload(ShelfPayload(true, "", cards, guideActivityClassName = ".Guide"), provider.metadata)
        assertEquals("tv", clean.shelfTitle)
        assertEquals(".Guide", clean.guideActivityClassName)
        assertEquals(24, clean.items.size)
        val card = clean.items.first()
        assertEquals("tv:item-0", card.id)
        assertEquals(120, card.title.length)
        assertEquals(160, card.subtitle?.length)
        assertNull(card.imageUri)
        assertNull(card.launchUri)
        assertNull(card.launchActivityClassName)
        assertEquals(-1, card.progressPercent)
        assertEquals("https://tv.example/logo", sanitizeImageUri("https://tv.example/logo"))
        assertNull(sanitizeImageUri("https://user:secret@tv.example/logo"))
        assertTrue(sanitizePayload(ShelfPayload(false, "", cards), provider.metadata).items.isEmpty())
    }

    @Test
    fun headerSanitizationPreservesAuthAndDropsInvalidOrOversizedValues() {
        val values = sequenceOf(
            "Authorization" to "Bearer example",
            "Host" to "different.example",
            "Bad Name" to "value",
            "X-Control" to "a\r\nb",
            "X-Large" to "a".repeat(2049),
            "X-Number" to 5,
        )
        assertEquals(mapOf("Authorization" to "Bearer example"), sanitizeHeaders(values))
        assertEquals(16, sanitizeHeaders((1..32).asSequence().map { "X-Example-$it" to "value" }).size)
    }

    @Test
    fun foregroundRefreshRespectsDisabledIdsAvailabilityAndCadence() = runTest {
        val enabled = Provider("enabled")
        val disabled = Provider("disabled")
        val empty = Provider("empty").apply { result = result.copy(items = emptyList()) }
        val unavailable = Provider("unavailable").apply { result = result.copy(available = false) }
        val failing = Provider("failing").apply { fail = true }
        var disabledIds = setOf(disabled.metadata.id)
        val manager = ShelfManager(
            backgroundScope,
            ShelfPluginRegistry { listOf(enabled, disabled, empty, unavailable, failing) },
        ) { disabledIds }

        manager.start()
        runCurrent()
        assertEquals(listOf("enabled"), manager.shelves.value.map { it.plugin.id })
        assertEquals(0, disabled.calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, enabled.calls)

        disabledIds = setOf(enabled.metadata.id)
        manager.refresh()
        assertTrue(manager.shelves.value.isEmpty())
        runCurrent()
        assertEquals(listOf("disabled"), manager.shelves.value.map { it.plugin.id })
        assertEquals(1, disabled.calls)
        manager.stop()
        manager.refresh()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, disabled.calls)
    }

    private class Provider(id: String) : ShelfPlugin {
        override val metadata = ShelfPluginMetadata(id, id, "", "1", "example.tv")
        var calls = 0
        var fail = false
        var result = ShelfData(true, id, listOf(ShelfCard("one", "Channel One")))

        override suspend fun fetchShelf(): ShelfData {
            calls++
            check(!fail)
            return result
        }
    }
}
