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

    @Test fun jellyfinCardsOpenGuideOrJellyfinTv() {
        val jf = channel.copy(id = "abc")
        val inGuide = jellyfinCard(jf, null, true, "host.pkg", guide, now)
        assertEquals(guide, inGuide.launchActivityClassName)
        assertEquals(mapOf(BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID to "abc"), inGuide.launchExtras)
        val launch = jellyfinCard(jf, null, false, "host.pkg", guide, now)
        assertEquals("org.jellyfin.androidtv", launch.launchPackageName)
        assertNull(launch.launchActivityClassName)
    }

    @Test fun constantsMatchContract() {
        assertEquals("livetv", BuiltInLiveTvShelfPlugin.PLUGIN_ID)
        assertEquals("com.stasisstate.slatetv.plugin.jellyfin.livetv", BuiltInLiveTvShelfPlugin.LEGACY_EXTERNAL_ID)
        assertEquals("tif_channel_id", BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID)
        assertEquals("channel_id", BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID)
    }
}
