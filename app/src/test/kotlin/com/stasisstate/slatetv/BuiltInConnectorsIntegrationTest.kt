package com.stasisstate.slatetv

import com.stasisstate.slatetv.connector.extensions.ExtensionsManagerActivity
import com.stasisstate.slatetv.connector.immich.BuiltInImmichPlugin
import com.stasisstate.slatetv.connector.immich.ImmichSettingsActivity
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin
import com.stasisstate.slatetv.connector.livetv.LiveTvGuideActivity
import com.stasisstate.slatetv.connector.livetv.LiveTvSettingsActivity
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import com.stasisstate.slatetv.shelf.ShelfLaunchDecision
import com.stasisstate.slatetv.shelf.ShelfPluginRegistry
import com.stasisstate.slatetv.shelf.decideShelfCardLaunch
import com.stasisstate.slatetv.shelf.isAllowedConnectorActivityLaunch
import com.stasisstate.slatetv.wallpaper.plugins.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInConnectorsIntegrationTest {
    private val host = "com.stasisstate.slatetv"

    private fun fakeBg(id: String) = object : BackgroundPlugin {
        override val metadata = PluginMetadata(id, id, "", "1.0")
        override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? = null
    }

    private fun fakeShelf(id: String) = object : ShelfPlugin {
        override val metadata = ShelfPluginMetadata(id, id, "", "1.0", host)
        override suspend fun fetchShelf(): ShelfData? = null
    }

    @Test
    fun builtInActivityLaunchAllowsOnlyAllowlistedConnectorActivities() {
        assertTrue(isAllowedConnectorActivityLaunch(host, host, ImmichSettingsActivity::class.java.name))
        assertTrue(isAllowedConnectorActivityLaunch(host, host, LiveTvSettingsActivity::class.java.name))
        assertTrue(isAllowedConnectorActivityLaunch(host, host, LiveTvGuideActivity::class.java.name))
        assertTrue(isAllowedConnectorActivityLaunch(host, host, ExtensionsManagerActivity::class.java.name))
        assertFalse(isAllowedConnectorActivityLaunch(host, host, "$host.ui.HomeActivity"))
        assertFalse(isAllowedConnectorActivityLaunch(host, host, ".ui.HomeActivity"))
        assertFalse(isAllowedConnectorActivityLaunch(host, "com.other.apk", ImmichSettingsActivity::class.java.name))
    }

    @Test
    fun shelfCardLaunchDecisionsEnforceSecurityBoundaries() {
        val tuneCard = ShelfCard(
            id = "ch-42",
            title = "Channel 42",
            launchUri = "content://android.media.tv/channel/42",
            launchExtras = mapOf(BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID to 42L),
        )
        assertEquals(
            ShelfLaunchDecision.TuneSystemChannel(42L),
            decideShelfCardLaunch(host, tuneCard),
        )

        val guideCard = ShelfCard(
            id = "ch-guide",
            title = "Guide",
            launchPackageName = host,
            launchActivityClassName = LiveTvGuideActivity::class.java.name,
        )
        assertEquals(
            ShelfLaunchDecision.BuiltInActivity(LiveTvGuideActivity::class.java.name),
            decideShelfCardLaunch(host, guideCard),
        )

        val evilHostCard = ShelfCard(
            id = "evil",
            title = "Evil",
            launchPackageName = host,
            launchActivityClassName = "$host.ui.HomeActivity",
        )
        assertEquals(ShelfLaunchDecision.Blocked, decideShelfCardLaunch(host, evilHostCard))

        val webCard = ShelfCard(id = "web", title = "Web", launchUri = "https://example.com/item")
        assertEquals(
            ShelfLaunchDecision.PublicUri("https://example.com/item"),
            decideShelfCardLaunch(host, webCard),
        )
        val marketCard = ShelfCard(id = "play", title = "Store", launchUri = "market://details?id=com.example")
        assertEquals(
            ShelfLaunchDecision.PublicUri("market://details?id=com.example"),
            decideShelfCardLaunch(host, marketCard),
        )
        for (badUri in listOf("file:///etc/hosts", "content://media/external/images/1", "intent://foo")) {
            assertEquals(
                ShelfLaunchDecision.Blocked,
                decideShelfCardLaunch(host, ShelfCard(id = "bad", title = "Bad", launchUri = badUri)),
            )
        }

        val externalCard = ShelfCard(id = "ext", title = "App", launchPackageName = "com.example.tvapp")
        assertEquals(
            ShelfLaunchDecision.ExternalPackage("com.example.tvapp"),
            decideShelfCardLaunch(host, externalCard),
        )

        val hostPkgCard = ShelfCard(id = "self", title = "Self", launchPackageName = host)
        assertEquals(ShelfLaunchDecision.Blocked, decideShelfCardLaunch(host, hostPkgCard))
    }

    @Test
    fun pluginRegistryMigratesLegacyImmichIdsAndLoadsJsonExtensions() {
        val immich = fakeBg(BuiltInImmichPlugin.PLUGIN_ID)
        val jsonExt = fakeBg("extjson:alpine")
        val registry = PluginRegistry(
            context = null,
            extraBuiltInPlugins = listOf(immich),
            jsonExtensionProvider = { listOf(jsonExt) },
            legacyBuiltInAliases = mapOf(BuiltInImmichPlugin.LEGACY_EXTERNAL_ID to BuiltInImmichPlugin.PLUGIN_ID),
        )
        registry.refreshExtensions()

        assertEquals(immich.metadata.id, registry.getPluginById(BuiltInImmichPlugin.LEGACY_EXTERNAL_ID).metadata.id)
        assertEquals(
            immich.metadata.id,
            registry.getPluginById("ext:com.stasisstate.slatetv.plugin.immich/.ImmichBackgroundPluginService").metadata.id,
        )
        assertEquals(jsonExt.metadata.id, registry.getPluginById("extjson:alpine").metadata.id)
    }

    @Test
    fun shelfRegistryMigratesLegacyJellyfinComponentIdToBuiltInLiveTv() {
        val registry = ShelfPluginRegistry { listOf(fakeShelf(BuiltInLiveTvShelfPlugin.PLUGIN_ID)) }
        registry.refresh()

        val migrated = registry.migrateDisabledIds(
            setOf("shelf:com.stasisstate.slatetv.plugin.jellyfin/.JellyfinShelfPluginService"),
        )
        assertEquals(setOf(BuiltInLiveTvShelfPlugin.PLUGIN_ID), migrated)
    }
}
