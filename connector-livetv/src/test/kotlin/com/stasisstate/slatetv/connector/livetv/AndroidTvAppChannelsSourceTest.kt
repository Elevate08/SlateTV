package com.stasisstate.slatetv.connector.livetv

import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms as Programs
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_APP_CHANNEL_PACKAGE
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_PREVIEW_PROGRAM_ID
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_WATCH_NEXT_PROGRAM_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AndroidTvAppChannelsSourceTest {
    private val host = "com.slatetv.host"
    private val jellyfin = "org.jellyfin.androidtv"
    private val other = "com.other.publisher"
    private val hidden = AppChannel.Preview(2L, jellyfin, "Jellyfin", "Next Up", false)
    private val visible = AppChannel.Preview(1L, jellyfin, "Jellyfin", "Latest Media", true)

    private fun channel(id: Long, pkg: String, name: String?, browsable: Boolean = true) =
        TifChannelRow(id, null, name, pkg, Channels.TYPE_PREVIEW, browsable)

    private class Reader(
        val channels: List<TifChannelRow> = emptyList(),
        val previews: List<AppProgramRow> = emptyList(),
        val watchNext: List<AppProgramRow> = emptyList(),
    ) : AppChannelsReader {
        val previewQueries = mutableListOf<Long>()
        var watchNextQueries = 0
        override fun channels() = channels
        override fun previewPrograms(channelId: Long): List<AppProgramRow> {
            previewQueries += channelId
            return previews
        }
        override fun watchNextPrograms(): List<AppProgramRow> {
            watchNextQueries++
            return watchNext
        }
        override fun appLabel(packageName: String) = if (packageName == "org.jellyfin.androidtv") "Jellyfin" else "Other TV"
    }

    @Test fun discoveryIncludesNonBrowsablePreviewChannelsAndUsesPublisherLabels() {
        val reader = Reader(channels = listOf(
            channel(6, other, "Movies"),
            channel(2, jellyfin, " Next Up ", browsable = false),
            channel(1, jellyfin, "Latest Media"),
            channel(1, jellyfin, "Latest Media"),
            channel(3, host, "Slate items"),
            channel(4, jellyfin, "  "),
            channel(5, jellyfin, "Live broadcast").copy(type = "TYPE_ATSC_T"),
        ))
        val discovered = AndroidTvAppChannelsSource(reader, host).discover()
        val movies = AppChannel.Preview(6, other, "Other TV", "Movies", true)
        assertEquals(listOf(visible, hidden, movies, AppChannel.WatchNext), discovered)
        assertEquals("preview:$jellyfin:2", hidden.key)
        assertEquals("Jellyfin · Next Up", hidden.shelfTitle)
    }

    @Test fun previewUsesWeightThenIdAndKeepsOnlyTwentyVisibleRowsFromThePublisher() {
        val rows = (1L..22L).reversed().map { AppProgramRow(it, jellyfin, weight = 1) } + listOf(
            AppProgramRow(50, jellyfin, weight = 2),
            AppProgramRow(51, jellyfin, weight = 3, browsable = false),
            AppProgramRow(52, other, weight = 4),
            AppProgramRow(53, host, weight = 4),
        )
        val reader = Reader(previews = rows)
        val programs = AndroidTvAppChannelsSource(reader, host).programs(hidden)
        assertEquals(listOf(2L), reader.previewQueries)
        assertEquals(23, programs.itemCount)
        assertEquals(listOf(50L) + (1L..19L), programs.items.map { it.id })
    }

    @Test fun watchNextRanksByLastEngagementThenIdAcrossPublishers() {
        val visibleRows = (1L..24L).map {
            AppProgramRow(it, if (it % 2 == 0L) jellyfin else other, lastEngagementMs = it * 100L)
        }
        val reader = Reader(watchNext = listOf(AppProgramRow(50, host), AppProgramRow(51, other, browsable = false)) + visibleRows)
        val programs = AndroidTvAppChannelsSource(reader, host).programs(AppChannel.WatchNext)
        assertEquals(24, programs.itemCount)
        assertEquals(visibleRows.sortedByDescending { it.lastEngagementMs }.take(20), programs.items)
        assertEquals(setOf(jellyfin, other), programs.items.map { it.ownerPackage }.toSet())
    }

    @Test fun readerUsesUnfilteredTvProviderQueriesAndParsesRowsWithoutReadingIntentUris() {
        var previewChannel: Long? = null
        val queries = mutableListOf<List<String>>()
        val reader = ContentResolverAppChannelsReader(
            tvQuery = TvQuery { _, projection, selection, args, sort ->
                assertNull(selection)
                assertNull(args)
                assertNull(sort)
                queries += projection.toList()
                tvCursor(projection.toList(), listOf(mapOf(
                    Programs._ID to 42L, Programs.COLUMN_PACKAGE_NAME to jellyfin, Channels.COLUMN_DISPLAY_NAME to "My Media",
                    Channels.COLUMN_TYPE to Channels.TYPE_PREVIEW, Programs.COLUMN_TITLE to "Show",
                    Programs.COLUMN_EPISODE_TITLE to "Episode", Programs.COLUMN_SHORT_DESCRIPTION to "Description",
                    Programs.COLUMN_POSTER_ART_URI to "content://publisher/image", Programs.COLUMN_THUMBNAIL_URI to "https://example.com/thumb",
                    Programs.COLUMN_DURATION_MILLIS to 1000L, Programs.COLUMN_LAST_PLAYBACK_POSITION_MILLIS to 250L,
                    Programs.COLUMN_LIVE to 1, Programs.COLUMN_WEIGHT to 20L, Programs.COLUMN_BROWSABLE to 0,
                )))
            },
            labelFor = { "Jellyfin" },
            channelsUri = { null },
            previewUri = { channelId -> previewChannel = channelId; null },
            watchNextUri = { null },
        )
        val channel = reader.channels().single()
        val preview = reader.previewPrograms(42L).single()
        val watchNext = reader.watchNextPrograms().single()
        assertEquals("My Media", channel.displayName)
        assertEquals(42L, previewChannel)
        assertEquals(
            AppProgramRow(42, jellyfin, "Show", "Episode", "Description", "content://publisher/image",
                "https://example.com/thumb", 1000L, 250L, true, 20, false),
            preview,
        )
        assertEquals(preview.copy(weight = 0), watchNext)
        assertEquals(3, queries.size)
        assertFalse(queries[2].contains(Programs.COLUMN_WEIGHT))
        assertTrue(queries.none { Programs.COLUMN_INTENT_URI in it })
    }

    @Test fun shelfCardsCarryOnlyTypedRowReferencesAndDisplayProgramMetadata() {
        val row = AppProgramRow(7, jellyfin, "Show", "Episode", durationMs = 400, playbackPositionMs = 100, live = true)
        val preview = appProgramCard(hidden, row, "file:///cache/image")
        assertEquals(mapOf(EXTRA_PREVIEW_PROGRAM_ID to 7L, EXTRA_APP_CHANNEL_PACKAGE to jellyfin), preview.launchExtras)
        assertEquals(jellyfin, preview.launchPackageName)
        assertNull(preview.launchUri)
        assertNull(preview.launchActivityClassName)
        assertEquals("Episode", preview.subtitle)
        assertEquals("LIVE", preview.badge)
        assertEquals(25, preview.progressPercent)
        assertEquals("file:///cache/image", preview.imageUri)
        val watchNext = appProgramCard(AppChannel.WatchNext, row, null)
        assertEquals(mapOf(EXTRA_WATCH_NEXT_PROGRAM_ID to 7L, EXTRA_APP_CHANNEL_PACKAGE to jellyfin), watchNext.launchExtras)
        assertNull(watchNext.launchUri)
    }

    @Test fun webArtworkAllowsPublicHttpsAndRejectsCleartextPrivateIpsAndCredentials() {
        assertEquals("https://cdn.example.com/image?t=1", appChannelWebArtwork("https://cdn.example.com/image?t=1"))
        assertNull(appChannelWebArtwork("http://cdn.example.com/image"))
        assertNull(appChannelWebArtwork("http://192.168.1.10/image"))
        assertNull(appChannelWebArtwork("https://192.168.1.10/image"))
        assertNull(appChannelWebArtwork("https://127.0.0.1/image"))
        assertNull(appChannelWebArtwork("https://169.254.169.254/image"))
        assertNull(appChannelWebArtwork("https://localhost/image"))
        assertNull(appChannelWebArtwork("https://user:password@cdn.example.com/image"))
        assertNull(appChannelWebArtwork("file:///storage/image"))
        assertNull(appChannelWebArtwork("content://publisher/image"))
    }

    @Test fun builtInProviderReturnsOneShelfPerEnabledChannelAndUsesCurrentPreferences() = runTest {
        val reader = Reader(
            channels = listOf(channel(1, jellyfin, "Latest Media"), channel(2, jellyfin, "Next Up", browsable = false)),
            previews = listOf(AppProgramRow(9, jellyfin, "Preview")),
            watchNext = listOf(AppProgramRow(4, other, "Watch Next program")),
        )
        var config = AppChannelsConfig()
        var hasPermission = true
        val provider = BuiltInAppChannelsProvider(
            host, AndroidTvAppChannelsSource(reader, host), { config }, { hasPermission },
        ) { "file:///cache/${it.id}" }

        val defaultShelves = provider.shelfPlugins()
        assertEquals(listOf("appchannel:${visible.key}", "appchannel:watch_next"), defaultShelves.map { it.metadata.id })
        assertEquals("Watch Next program", defaultShelves.last().fetchShelf()?.items?.single()?.title)
        assertEquals(1, reader.watchNextQueries)
        assertTrue(reader.previewQueries.isEmpty())

        config = AppChannelsConfig(setOf(hidden.key), hasUserCustomized = true)
        val enabled = provider.shelfPlugins().single()
        assertEquals("Jellyfin · Next Up", enabled.metadata.name)
        assertEquals("file:///cache/9", enabled.fetchShelf()?.items?.single()?.imageUri)
        assertEquals(listOf(2L), reader.previewQueries)
        assertEquals(1, reader.watchNextQueries)

        hasPermission = false
        assertTrue(provider.shelfPlugins().isEmpty())
        assertFalse(enabled.fetchShelf()!!.available)
    }
}
