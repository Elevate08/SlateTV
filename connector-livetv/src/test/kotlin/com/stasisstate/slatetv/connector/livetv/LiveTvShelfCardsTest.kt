package com.stasisstate.slatetv.connector.livetv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveTvShelfCardsTest {
    private val now = Instant.parse("2026-11-19T12:06:00Z")
    private val program = Program("p", "News", "Noon", null, Instant.parse("2026-11-19T12:00:00Z"), Instant.parse("2026-11-19T12:30:00Z"), true)
    private val channel = Channel("42", "KABC", "7.1", null, program)
    private val guide = "com.example.Guide"

    @Test fun systemTuneCardCarriesChannelUriAndExtraWithoutAnyActivity() {
        val card = systemCard(channel, "file:///logo", inGuide = false, packageName = "host.pkg", guideActivity = guide, now = now)
        assertEquals("content://android.media.tv/channel/42", card.launchUri)
        assertEquals(mapOf(BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID to 42L), card.launchExtras)
        assertNull(card.launchActivityClassName)
        assertEquals("LIVE", card.badge)
        assertEquals(20, card.progressPercent)
        assertEquals("file:///logo", card.imageUri)
        assertTrue(card.subtitle!!.startsWith("News · Noon · 24m left"))
    }

    @Test fun systemGuideCardOpensGuideInProcess() {
        val card = systemCard(channel, null, inGuide = true, packageName = "host.pkg", guideActivity = guide, now = now)
        assertEquals("host.pkg", card.launchPackageName)
        assertEquals(guide, card.launchActivityClassName)
        assertEquals(mapOf(BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID to "42"), card.launchExtras)
        assertNull(card.launchUri)
    }

    @Test fun nonNumericChannelIdFallsBackToTheGuide() {
        val card = systemCard(channel.copy(id = "abc"), null, inGuide = false, packageName = "host.pkg", guideActivity = guide, now = now)
        assertEquals(guide, card.launchActivityClassName)
        assertEquals(mapOf(BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID to "abc"), card.launchExtras)
        assertNull(card.launchUri)
    }

    @Test fun unsetOrLegacyTapActionLaunchesDirectlyAndSavedGuideIsRespected() {
        assertEquals(CardTapAction.LAUNCH_CHANNEL, LiveTvConfig().cardTapAction)
        for (saved in listOf(null, "LAUNCH_JELLYFIN", "LAUNCH_CHANNEL", "unknown")) {
            assertEquals(CardTapAction.LAUNCH_CHANNEL, parseCardTapAction(saved))
        }
        assertEquals(CardTapAction.OPEN_GUIDE, parseCardTapAction("OPEN_GUIDE"))
    }

    @Test fun constantsMatchContract() {
        assertEquals("livetv", BuiltInLiveTvShelfPlugin.PLUGIN_ID)
        assertEquals("com.stasisstate.slatetv.plugin.jellyfin.livetv", BuiltInLiveTvShelfPlugin.LEGACY_EXTERNAL_ID)
        assertEquals("tif_channel_id", BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID)
        assertEquals("channel_id", BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID)
    }
}
