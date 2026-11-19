package com.stasisstate.slatetv.connector.livetv

import android.database.Cursor
import android.media.tv.TvContract
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class SystemTvChannelsSourceTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun channel(
        id: Long, number: String? = "$id", name: String? = "Channel $id",
        type: String? = "TYPE_OTHER", browsable: Boolean = true,
    ) = TifChannelRow(id, number, name, "com.example.input", type, browsable)

    private fun program(channel: Long, start: Long, end: Long, title: String? = "Show", episode: String? = null, desc: String? = null) =
        TifProgramRow(channel, title, episode, desc, now + start * minute, now + end * minute)

    private class FakeReader(
        val channels: () -> List<TifChannelRow> = { emptyList() },
        val programs: () -> List<TifProgramRow> = { emptyList() },
    ) : TvListingsReader {
        var window: Pair<Long, Long>? = null
        var requestedChannelIds: List<Long>? = null
        override fun channels() = channels.invoke()
        override fun programs(channelIds: List<Long>, fromMs: Long, toMs: Long): List<TifProgramRow> {
            requestedChannelIds = channelIds
            window = fromMs to toMs
            return programs.invoke()
        }
    }

    private fun load(reader: FakeReader) = SystemTvChannelsSource(reader) { now }.load()

    /** Minimal in-memory [Cursor] backed by a dynamic proxy (no Robolectric needed). */
    private fun cursor(columns: List<String>, rows: List<Map<String, Any?>>): Cursor {
        var position = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "moveToNext" -> (++position < rows.size)
                "getColumnIndex" -> columns.indexOf(args[0] as String)
                "isNull" -> rows[position][columns[args[0] as Int]] == null
                "getLong" -> (rows[position][columns[args[0] as Int]] as Number).toLong()
                "getInt" -> (rows[position][columns[args[0] as Int]] as Number).toInt()
                "getString" -> rows[position][columns[args[0] as Int]]?.toString()
                "close" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Cursor
    }

    @Test fun filtersPreviewAndNonBrowsableChannels() {
        val reader = FakeReader(channels = {
            listOf(
                channel(1),
                channel(2, type = TvContract.Channels.TYPE_PREVIEW),
                channel(3, browsable = false),
                channel(4, number = null, name = null),
                channel(1), // duplicate id
                channel(5, type = null),
            )
        })
        val ready = load(reader) as SystemTvResult.Ready
        assertEquals(listOf("1", "5"), ready.channels.map { it.id })
    }

    @Test fun sortsChannelsByNaturalNumberThenName() {
        val reader = FakeReader(channels = {
            listOf(
                channel(1, "10", "Ten"), channel(2, "2", "Two"), channel(3, "7.10", "B"),
                channel(4, "7.2", "A"), channel(5, null, "No number"), channel(6, "2", "Alpha"),
            )
        })
        val ready = load(reader) as SystemTvResult.Ready
        assertEquals(listOf("Alpha", "Two", "A", "B", "Ten", "No number"), ready.channels.map { it.name })
    }

    @Test fun selectsCurrentAndUpcomingPrograms() {
        val reader = FakeReader(
            channels = { listOf(channel(1), channel(2), channel(3)) },
            programs = {
                listOf(
                    program(1, -30, 0, "Ended exactly now"),
                    program(1, -10, 20, "On now", episode = "Pilot", desc = "Synopsis"),
                    program(1, 20, 50, "Next"),
                    program(1, 50, 80, "After next"),
                    program(1, 300, 330, "Beyond four hours"),
                    program(2, 15, 45, "Only upcoming"),
                    program(3, -5, -5, "Zero length"),
                )
            },
        )
        val ready = load(reader) as SystemTvResult.Ready
        val one = ready.channels.first { it.id == "1" }
        assertEquals("On now", one.current?.name)
        assertEquals("On now · Pilot", one.current?.title)
        assertEquals("Synopsis", one.current?.overview)
        assertTrue(one.current!!.isLive)
        assertEquals(33, one.current!!.progressPercent(java.time.Instant.ofEpochMilli(now)))
        assertEquals("Next", ready.upcoming["1"]?.name)
        assertNull(ready.channels.first { it.id == "2" }.current)
        assertEquals("Only upcoming", ready.upcoming["2"]?.name)
        assertNull(ready.channels.first { it.id == "3" }.current)
        assertNull(ready.upcoming["3"])
        assertEquals(now - 1 to now + UPCOMING_WINDOW_MS, reader.window)
    }

    @Test fun overlappingProgramsPreferLatestStart() {
        val (current, next) = SystemTvChannelsSource.selectPrograms(
            listOf(program(1, -60, 30, "Long"), program(1, -5, 30, "Recent")), now,
        )
        assertEquals("Recent", current?.title)
        assertNull(next)
    }

    @Test fun missingChannelsPermissionIsNeedsPermission() {
        val reader = FakeReader(channels = { throw SecurityException("READ_TV_LISTINGS") })
        assertEquals(SystemTvResult.NeedsPermission, load(reader))
    }

    @Test fun missingProgramsPermissionIsNeedsPermission() {
        val reader = FakeReader(channels = { listOf(channel(1)) }, programs = { throw SecurityException("denied") })
        assertEquals(SystemTvResult.NeedsPermission, load(reader))
    }

    @Test fun programFailureKeepsChannelsWithoutGuideData() {
        val reader = FakeReader(channels = { listOf(channel(1)) }, programs = { throw IllegalStateException("boom") })
        val ready = load(reader) as SystemTvResult.Ready
        assertEquals(1, ready.channels.size)
        assertNull(ready.channels.single().current)
    }

    @Test fun noBrowsableChannelsIsReadyAndEmpty() {
        val ready = load(FakeReader(channels = { listOf(channel(1, browsable = false)) })) as SystemTvResult.Ready
        assertTrue(ready.channels.isEmpty())
    }

    @Test fun parsesChannelCursorRows() {
        val columns = listOf(
            TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
        )
        val rows = parseChannelRows(cursor(columns, listOf(
            mapOf(columns[0] to 7L, columns[1] to "7.1", columns[2] to "KABC", columns[3] to "com.example.tuner",
                columns[4] to "TYPE_ATSC_T", columns[5] to 1),
            mapOf(columns[0] to 8L, columns[1] to null, columns[2] to "Preview", columns[3] to null,
                columns[4] to TvContract.Channels.TYPE_PREVIEW, columns[5] to 0),
            mapOf(columns[0] to null, columns[1] to "9"), // no id → skipped
        )))
        assertEquals(2, rows.size)
        assertEquals(TifChannelRow(7, "7.1", "KABC", "com.example.tuner", "TYPE_ATSC_T", true), rows[0])
        assertEquals(8L, rows[1].id)
        assertFalse(rows[1].browsable)
        assertEquals(TvContract.Channels.TYPE_PREVIEW, rows[1].type)
    }

    @Test fun parsesProgramCursorRowsAndFallsBackToLongDescription() {
        val columns = listOf(
            TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_EPISODE_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_LONG_DESCRIPTION,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
        )
        val rows = parseProgramRows(cursor(columns, listOf(
            mapOf(columns[0] to 7L, columns[1] to "News", columns[2] to "Ep", columns[3] to null,
                columns[4] to "Long text", columns[5] to 1000L, columns[6] to 2000L),
            mapOf(columns[0] to 7L, columns[1] to "Bad", columns[5] to null, columns[6] to 2000L), // missing start → skipped
        )))
        assertEquals(1, rows.size)
        assertEquals(TifProgramRow(7, "News", "Ep", "Long text", 1000, 2000), rows.single())
    }

    @Test fun readerQueriesEachChannelWithNullSelectionAndNullSortOrder() {
        data class Call(val uri: Any?, val selection: String?, val args: Array<String>?, val sort: String?)
        val calls = mutableListOf<Call>()
        val built = mutableListOf<Triple<Long, Long, Long>>()
        val programColumns = listOf(
            TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_EPISODE_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_LONG_DESCRIPTION,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
        )
        val reader = ContentResolverTvListingsReader(
            tvQuery = TvQuery { uri, _, selection, args, sort ->
                calls += Call(uri, selection, args, sort)
                cursor(programColumns, listOf(mapOf(
                    programColumns[0] to built.last().first, programColumns[1] to "T", programColumns[5] to 1L, programColumns[6] to 2L,
                )))
            },
            programsUri = { channelId, from, to -> built += Triple(channelId, from, to); null },
        )
        val rows = reader.programs(listOf(7L, 9L), 100L, 200L)
        assertEquals(listOf(Triple(7L, 100L, 200L), Triple(9L, 100L, 200L)), built)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.selection == null && it.args == null && it.sort == null })
        assertEquals(listOf(7L, 9L), rows.map { it.channelId })
    }

    @Test fun readerPropagatesSecurityExceptionFromProvider() {
        val reader = ContentResolverTvListingsReader(
            tvQuery = TvQuery { _, _, _, _, _ -> throw SecurityException("READ_TV_LISTINGS") },
            channelsUri = { null },
            programsUri = { _, _, _ -> null },
        )
        assertThrows(SecurityException::class.java) { reader.channels() }
        assertEquals(SystemTvResult.NeedsPermission, SystemTvChannelsSource(reader) { now }.load())
    }
}
