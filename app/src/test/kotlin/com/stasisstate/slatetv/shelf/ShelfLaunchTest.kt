package com.stasisstate.slatetv.shelf

import com.stasisstate.slatetv.connector.livetv.AppChannelsSettingsActivity
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_APP_CHANNEL_PACKAGE
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_PREVIEW_PROGRAM_ID
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_WATCH_NEXT_PROGRAM_ID
import com.stasisstate.slatetv.plugin.api.ShelfCard
import org.junit.Assert.*
import org.junit.Test

class ShelfLaunchTest {
    private val host = "com.stasisstate.slatetv"
    private val owner = "com.example.publisher"

    @Test fun legacyJellyfinChannelExtraIsIgnoredAndOpensTheAppLauncherOnly() {
        // Cards from the removed Jellyfin server source carried "jellyfin_channel_id"; it no longer has any meaning.
        val card = ShelfCard(
            "id", "Channel", launchPackageName = "org.jellyfin.androidtv",
            launchExtras = mapOf("jellyfin_channel_id" to "0123456789abcdef0123456789abcdef"),
        )
        assertEquals(ShelfLaunchDecision.ExternalPackage("org.jellyfin.androidtv"), decideShelfCardLaunch(host, card))
        assertEquals(ShelfLaunchDecision.Blocked, decideShelfCardLaunch(host, card.copy(launchPackageName = null)))
    }

    @Test fun dispatchesBothProgramKindsWithTheRowIdAndOwner() {
        assertEquals(
            ShelfLaunchDecision.LaunchPreviewProgram(42L, owner),
            decideShelfCardLaunch(host, programCard(EXTRA_PREVIEW_PROGRAM_ID)),
        )
        assertEquals(
            ShelfLaunchDecision.LaunchWatchNextProgram(42L, owner),
            decideShelfCardLaunch(host, programCard(EXTRA_WATCH_NEXT_PROGRAM_ID)),
        )
    }

    @Test fun rejectsProgramCardsWithWrongOwnersOrUntrustedDestinations() {
        val card = programCard(EXTRA_PREVIEW_PROGRAM_ID)
        val invalid = listOf(
            card.copy(launchPackageName = null),
            card.copy(launchPackageName = "com.other.app"),
            card.copy(launchPackageName = host, launchExtras = card.launchExtras + (EXTRA_APP_CHANNEL_PACKAGE to host)),
            card.copy(launchPackageName = "bad/pkg", launchExtras = card.launchExtras + (EXTRA_APP_CHANNEL_PACKAGE to "bad/pkg")),
            card.copy(launchUri = "https://example.com/item"),
            card.copy(launchActivityClassName = AppChannelsSettingsActivity::class.java.name),
        )
        invalid.forEach { assertEquals(ShelfLaunchDecision.Blocked, decideShelfCardLaunch(host, it)) }
    }

    @Test fun rejectsAmbiguousOrMalformedProgramReferences() {
        val card = programCard(EXTRA_PREVIEW_PROGRAM_ID)
        val invalid = listOf(
            card.launchExtras - EXTRA_APP_CHANNEL_PACKAGE,
            card.launchExtras - EXTRA_PREVIEW_PROGRAM_ID,
            card.launchExtras + (EXTRA_PREVIEW_PROGRAM_ID to -1L),
            card.launchExtras + (EXTRA_PREVIEW_PROGRAM_ID to "42"),
            card.launchExtras + (EXTRA_WATCH_NEXT_PROGRAM_ID to 17L),
        )
        invalid.forEach { assertEquals(ShelfLaunchDecision.Blocked, decideShelfCardLaunch(host, card.copy(launchExtras = it))) }
    }

    @Test fun appChannelSettingsUsesTheHostActivityAllowlist() {
        val activity = AppChannelsSettingsActivity::class.java.name
        assertTrue(isAllowedConnectorActivityLaunch(host, host, activity))
        assertFalse(isAllowedConnectorActivityLaunch(host, owner, activity))
    }

    private fun programCard(rowKey: String) = ShelfCard(
        "id", "Program", launchPackageName = owner,
        launchExtras = mapOf(rowKey to 42L, EXTRA_APP_CHANNEL_PACKAGE to owner),
    )
}
