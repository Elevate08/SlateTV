package com.stasisstate.slatetv.shelf

import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfItem
import com.stasisstate.slatetv.plugin.api.ShelfPayload
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun legacyManifestIdMigratesOnlyWhenClaimedByExactlyOneService() {
        val jellyfin = Provider("shelf:jf/.Svc", "plugin.jellyfin.livetv")
        val spoofA = Provider("shelf:a/.Svc", "plugin.dup")
        val spoofB = Provider("shelf:b/.Svc", "plugin.dup")
        val registry = ShelfPluginRegistry { listOf(jellyfin, spoofA, spoofB) }
        registry.refresh()

        assertEquals(mapOf("plugin.jellyfin.livetv" to "shelf:jf/.Svc"), registry.legacyAliases)
        assertEquals("shelf:jf/.Svc", registry.canonicalId("plugin.jellyfin.livetv"))
        assertEquals("plugin.dup", registry.canonicalId("plugin.dup"))
        assertEquals("unknown", registry.canonicalId("unknown"))
        assertEquals("shelf:a/.Svc", registry.canonicalId("shelf:a/.Svc"))

        val saved = setOf("plugin.jellyfin.livetv", "plugin.dup", "shelf:b/.Svc")
        assertEquals(setOf("shelf:jf/.Svc", "plugin.dup", "shelf:b/.Svc"), registry.migrateDisabledIds(saved))
        assertTrue(registry.isDisabled("shelf:jf/.Svc", saved))
        assertTrue(registry.isDisabled("shelf:b/.Svc", saved))
        // Ambiguous legacy disable must not silently disable either claimant
        assertFalse(registry.isDisabled("shelf:a/.Svc", saved))
        assertFalse(registry.isDisabled("shelf:jf/.Svc", emptySet()))
    }

    @Test
    fun migrationIsIdempotentAndCanonicalIdsCannotBeHijackedByLegacyClaims() {
        val real = Provider("shelf:real/.Svc")
        val hijacker = Provider("shelf:evil/.Svc", "shelf:real/.Svc")
        val registry = ShelfPluginRegistry { listOf(real, hijacker) }
        registry.refresh()
        assertTrue(registry.legacyAliases.isEmpty())
        val once = registry.migrateDisabledIds(setOf("shelf:real/.Svc"))
        assertEquals(setOf("shelf:real/.Svc"), once)
        assertEquals(once, registry.migrateDisabledIds(once))
        assertFalse(registry.isDisabled("shelf:evil/.Svc", once))
    }

    @Test
    fun registrySnapshotUpdatesAliasesAndPluginsTogether() {
        var installed = listOf<ShelfPlugin>(Provider("shelf:jf/.Svc", "legacy.jf"))
        val registry = ShelfPluginRegistry { installed }
        assertEquals("legacy.jf", registry.run { refresh(); legacyAliases.keys.single() })
        installed = listOf(Provider("shelf:other/.Svc"))
        registry.refresh()
        assertTrue(registry.legacyAliases.isEmpty())
        assertEquals(listOf("shelf:other/.Svc"), registry.plugins.map { it.metadata.id })
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
        assertEquals(
            "content://example.tv.media/abc123",
            sanitizeImageUri("content://example.tv.media/abc123", "example.tv", ownsAll),
        )
        assertNull(sanitizeImageUri("content://other.app.media/abc123", "example.tv", ownsAll))
        assertNull(sanitizeImageUri("content://example.tv.media/sub/path", "example.tv", ownsAll))
        assertNull(sanitizeImageUri("content://example.tv.media/..%2Fsecret", "example.tv", ownsAll))
        assertNull(sanitizeImageUri("content://example.tv.media/abc?foo=bar", "example.tv", ownsAll))
        assertTrue(sanitizePayload(ShelfPayload(false, "", cards), provider.metadata).items.isEmpty())

        // Strict launchUri scheme allowlist & content:// header stripping
        val allowed = ShelfItem(
            id = "ok",
            title = "Channel",
            imageUri = "content://example.tv.media/logo01",
            imageHeaders = android.os.Bundle(),
            launchUri = "jellyfin://channel/1",
        )
        val blocked = ShelfItem(
            id = "bad",
            title = "Bad Scheme",
            launchUri = "tel:5551234",
        )
        val checked = sanitizePayload(ShelfPayload(true, "TV", listOf(allowed, blocked)), provider.metadata, ownsAll)
        assertEquals("content://example.tv.media/logo01", checked.items[0].imageUri)
        assertEquals(emptyMap<String, String>(), checked.items[0].imageHeaders)
        assertEquals("jellyfin://channel/1", checked.items[0].launchUri)
        assertNull(checked.items[1].launchUri)
    }

    @Test
    fun contentUriRequiresVerifiedProviderOwnership() {
        val uri = "content://example.tv.media/abc123"
        // Default verifier fails closed: content:// is never accepted without an ownership check.
        assertNull(sanitizeImageUri(uri, "example.tv"))

        val asked = mutableListOf<Pair<String, String>>()
        val recording: ProviderOwnershipVerifier = { authority, pkg -> asked += authority to pkg; true }
        assertEquals(uri, sanitizeImageUri(uri, "example.tv", recording))
        assertEquals(listOf("example.tv.media" to "example.tv"), asked)

        // Unregistered / rogue-owned provider -> rejected, in both entry points.
        assertNull(sanitizeImageUri(uri, "example.tv") { _, _ -> false })
        val item = ShelfItem(id = "logo", title = "Channel", imageUri = uri)
        val metadata = Provider("tv").metadata
        assertNull(sanitizePayload(ShelfPayload(true, "TV", listOf(item)), metadata).items.single().imageUri)
        assertNull(
            sanitizePayload(ShelfPayload(true, "TV", listOf(item)), metadata) { _, _ -> false }
                .items.single().imageUri,
        )
        assertEquals(uri, sanitizePayload(ShelfPayload(true, "TV", listOf(item)), metadata, ownsAll).items.single().imageUri)

        // Verifier is not consulted for malformed URIs, and HTTP(S) never needs it.
        asked.clear()
        assertNull(sanitizeImageUri("content://example.tv.media/abc?x=1", "example.tv", recording))
        assertTrue(asked.isEmpty())
        assertEquals("https://tv.example/logo", sanitizeImageUri("https://tv.example/logo", "example.tv"))
    }

    @Test
    fun providerOwnershipRequiresMatchingPackageUidAndEnabledState() {
        val owner = appInfo("example.tv", uid = 10_123)
        assertTrue(isProviderInfoOwnedBy(providerInfo("example.tv", 10_123), "example.tv", owner))

        // Unregistered authority or uninstalled owner.
        assertFalse(isProviderInfoOwnedBy(null, "example.tv", owner))
        assertFalse(isProviderInfoOwnedBy(providerInfo("example.tv", 10_123), "example.tv", null))
        // Authority squatted by a different APK.
        assertFalse(isProviderInfoOwnedBy(providerInfo("rogue.app", 10_999), "example.tv", owner))
        // Same package name reported, but provider runs under a different UID.
        assertFalse(isProviderInfoOwnedBy(providerInfo("example.tv", 10_999), "example.tv", owner))
        // Owner info for a different package.
        assertFalse(
            isProviderInfoOwnedBy(providerInfo("example.tv", 10_123), "example.tv", appInfo("other", 10_123)),
        )
        // Missing application info / disabled component or app.
        assertFalse(
            isProviderInfoOwnedBy(
                providerInfo("example.tv", 10_123).apply { applicationInfo = null }, "example.tv", owner,
            ),
        )
        assertFalse(
            isProviderInfoOwnedBy(providerInfo("example.tv", 10_123).apply { enabled = false }, "example.tv", owner),
        )
        assertFalse(
            isProviderInfoOwnedBy(
                providerInfo("example.tv", 10_123).apply { applicationInfo.enabled = false }, "example.tv", owner,
            ),
        )
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
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

    private class Provider(id: String, override val legacyManifestId: String? = null) : ShelfPlugin, LegacyManifestIdClaimant {
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

    private val ownsAll: ProviderOwnershipVerifier = { _, _ -> true }

    private fun appInfo(pkg: String, uid: Int) = android.content.pm.ApplicationInfo().apply {
        packageName = pkg
        this.uid = uid
        enabled = true
    }

    private fun providerInfo(pkg: String, uid: Int) = android.content.pm.ProviderInfo().apply {
        packageName = pkg
        authority = "example.tv.media"
        enabled = true
        applicationInfo = appInfo(pkg, uid)
    }

}
