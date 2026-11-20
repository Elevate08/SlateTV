package com.stasisstate.slatetv.shelf

import com.stasisstate.slatetv.NetworkClientFactory
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfHostingTest {
    @Test
    fun registryDeduplicatesIdsAndRemovesDisabledExtensions() {
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
    fun legacyLiveTvIdsMigrateToBuiltInLiveTvId() {
        val registry = ShelfPluginRegistry { listOf(Provider(BuiltInLiveTvShelfPlugin.PLUGIN_ID)) }
        registry.refresh()

        assertEquals(BuiltInLiveTvShelfPlugin.PLUGIN_ID, registry.canonicalId(BuiltInLiveTvShelfPlugin.LEGACY_EXTERNAL_ID))
        assertEquals(
            BuiltInLiveTvShelfPlugin.PLUGIN_ID,
            registry.canonicalId("shelf:com.stasisstate.slatetv.plugin.jellyfin/.JellyfinShelfPluginService"),
        )
        assertEquals("unknown", registry.canonicalId("unknown"))

        val saved = setOf(BuiltInLiveTvShelfPlugin.LEGACY_EXTERNAL_ID, "extshelf:news")
        assertEquals(
            setOf(BuiltInLiveTvShelfPlugin.PLUGIN_ID, "extshelf:news"),
            registry.migrateDisabledIds(saved),
        )
        assertTrue(registry.isDisabled(BuiltInLiveTvShelfPlugin.PLUGIN_ID, saved))
        assertFalse(registry.isDisabled(BuiltInLiveTvShelfPlugin.PLUGIN_ID, emptySet()))
    }

    @Test
    fun foregroundRefreshAppliesAppChannelOptInsAlongsideLiveTvAndExtensions() = runTest {
        val liveTv = Provider(BuiltInLiveTvShelfPlugin.PLUGIN_ID)
        val preview = Provider("appchannel:preview:com.example.app:42")
        val watchNext = Provider("appchannel:watch_next")
        val extension = Provider("extshelf:news")
        var appChannels = listOf(preview)
        val registry = ShelfPluginRegistry { listOf(liveTv) + appChannels + extension }
        val manager = ShelfManager(backgroundScope, registry, StandardTestDispatcher(testScheduler)) { emptySet() }

        manager.start()
        runCurrent()
        assertEquals(listOf(liveTv, preview, extension), registry.plugins)
        assertEquals(listOf(liveTv, preview, extension).map { it.metadata.id }, manager.shelves.value.map { it.plugin.id })

        appChannels = listOf(watchNext)
        manager.refresh()
        runCurrent()
        assertEquals(listOf(liveTv, watchNext, extension), registry.plugins)
        assertEquals(listOf(liveTv, watchNext, extension).map { it.metadata.id }, manager.shelves.value.map { it.plugin.id })
        manager.stop()
    }

    @Test
    fun headerSanitizationPreservesAuthAndDropsInvalidOrOversizedValues() {
        val values = sequenceOf(
            "Authorization" to "Bearer example",
            "Host" to "different.example",
            "Bad Name" to "value",
            "X-Control" to "a\r\nb",
            "X-Large" to "a".repeat(2049),
        )
        assertEquals(mapOf("Authorization" to "Bearer example"), NetworkClientFactory.sanitizeHeaders(values))
        assertEquals(16, NetworkClientFactory.sanitizeHeaders((1..32).asSequence().map { "X-Example-$it" to "value" }).size)
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { disabledIds }

        manager.start()
        runCurrent()
        assertEquals(listOf("enabled"), manager.shelves.value.map { it.plugin.id })
        assertEquals(0, disabled.calls)
        // "failing" is transient: short retries at +4s and +10s, then back to the 60s poll.
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(2, enabled.calls)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, enabled.calls)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(3, enabled.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, enabled.calls)

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

    @Test
    fun transientFailuresPreserveLastGoodShelfUpToTwoCyclesWhileUnavailableHidesImmediately() = runTest {
        val flaky = Provider("flaky")
        val manager = ShelfManager(
            backgroundScope,
            ShelfPluginRegistry { listOf(flaky) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { emptySet() }

        manager.start()
        runCurrent()
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })

        // First & second transient failures preserve the last good shelf
        flaky.fail = true
        manager.refresh()
        runCurrent()
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })

        manager.refresh()
        runCurrent()
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })

        // Third consecutive transient failure clears the stale shelf
        manager.refresh()
        runCurrent()
        assertTrue(manager.shelves.value.isEmpty())

        // Recovery restores shelf, then explicit available=false hides it immediately
        flaky.fail = false
        manager.refresh()
        runCurrent()
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })

        flaky.result = flaky.result.copy(available = false, items = emptyList())
        manager.refresh()
        runCurrent()
        assertTrue(manager.shelves.value.isEmpty())
        manager.stop()
    }

    @Test
    fun transientNullSchedulesShortRetriesAndRecoversBeforeFullPoll() = runTest {
        val flaky = Provider("flaky")
        val manager = ShelfManager(
            backgroundScope,
            ShelfPluginRegistry { listOf(flaky) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { emptySet() }

        manager.start()
        runCurrent()
        assertEquals(1, flaky.calls)
        assertEquals(60_000L, manager.nextDelayMs())

        // TV wake: provider returns null (transient). Shelf is preserved and a 4s retry is scheduled.
        flaky.returnNull = true
        manager.refresh()
        runCurrent()
        assertEquals(2, flaky.calls)
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })
        assertEquals(4_000L, manager.nextDelayMs())

        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(2, flaky.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, flaky.calls)
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })
        assertEquals(10_000L, manager.nextDelayMs())

        // Second retry after 10s recovers well before the 60s poll; cadence returns to normal.
        flaky.returnNull = false
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(3, flaky.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, flaky.calls)
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })
        assertEquals(60_000L, manager.nextDelayMs())
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(4, flaky.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5, flaky.calls)
        manager.stop()
    }

    @Test
    fun shortRetryStopsAfterGraceWindowAndConfirmedUnavailableHidesWithoutRetry() = runTest {
        val flaky = Provider("flaky")
        val manager = ShelfManager(
            backgroundScope,
            ShelfPluginRegistry { listOf(flaky) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { emptySet() }

        manager.start()
        runCurrent()
        flaky.returnNull = true
        manager.refresh()
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, flaky.calls)
        assertTrue(manager.shelves.value.isEmpty())
        assertEquals(60_000L, manager.nextDelayMs())
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(4, flaky.calls)

        flaky.returnNull = false
        manager.refresh()
        runCurrent()
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })
        flaky.result = flaky.result.copy(available = false, items = emptyList())
        manager.refresh()
        runCurrent()
        assertTrue(manager.shelves.value.isEmpty())
        assertEquals(60_000L, manager.nextDelayMs())
        manager.stop()
    }

    @Test
    fun stopCancelsPendingShortRetry() = runTest {
        val flaky = Provider("flaky")
        val manager = ShelfManager(
            backgroundScope,
            ShelfPluginRegistry { listOf(flaky) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { emptySet() }

        manager.start()
        runCurrent()
        flaky.returnNull = true
        manager.refresh()
        runCurrent()
        assertEquals(4_000L, manager.nextDelayMs())
        manager.stop()
        assertEquals(60_000L, manager.nextDelayMs())
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, flaky.calls)
        assertEquals(listOf("flaky"), manager.shelves.value.map { it.plugin.id })
        flaky.returnNull = false
        manager.start()
        runCurrent()
        assertEquals(3, flaky.calls)
        assertEquals(60_000L, manager.nextDelayMs())
        manager.stop()
    }

    private class Provider(id: String) : ShelfPlugin {
        override val metadata = ShelfPluginMetadata(id, id, "", "1", "com.stasisstate.slatetv")
        var calls = 0
        var fail = false
        var returnNull = false
        var result = ShelfData(true, id, listOf(ShelfCard("one", "Channel One")))

        override suspend fun fetchShelf(): ShelfData? {
            calls++
            check(!fail)
            return if (returnNull) null else result
        }
    }
}
