package com.stasisstate.slatetv.connector.livetv

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import java.time.Instant

internal const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
internal const val LIVE_CHANNELS_PACKAGE = "com.android.tv"
internal const val UPCOMING_WINDOW_MS = 4L * 60 * 60 * 1000
private const val MAX_PROGRAM_ROWS = 5_000
private const val MAX_CHANNEL_ROWS = 2_000
private const val MAX_LOGO_READ = 2L * 1024 * 1024

internal data class TifChannelRow(
    val id: Long,
    val displayNumber: String?,
    val displayName: String?,
    val packageName: String?,
    val type: String?,
    val browsable: Boolean,
)

internal data class TifProgramRow(
    val channelId: Long,
    val title: String?,
    val episodeTitle: String?,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/** Source of raw TV Provider rows; throws [SecurityException] when READ_TV_LISTINGS is not granted. */
internal interface TvListingsReader {
    fun channels(): List<TifChannelRow>
    fun programs(channelIds: List<Long>, fromMs: Long, toMs: Long): List<TifProgramRow>
}

internal sealed interface SystemTvResult {
    /** READ_TV_LISTINGS has not been granted; the shelf should hide and Settings should explain how to grant it. */
    data object NeedsPermission : SystemTvResult

    /** [channels] may be empty (no browsable channels set up in Live Channels). */
    data class Ready(val channels: List<Channel>, val upcoming: Map<String, Program>) : SystemTvResult
}

/** Reads browsable broadcast channels plus on-now / up-next programs from the Android TV system EPG. */
internal class SystemTvChannelsSource(
    private val reader: TvListingsReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun load(): SystemTvResult {
        val now = clock()
        val channelRows = try {
            reader.channels()
        } catch (_: SecurityException) {
            return SystemTvResult.NeedsPermission
        }
        val visible = channelRows.filter { it.browsable && it.type != TvContract.Channels.TYPE_PREVIEW }
            .filter { !it.displayName.isNullOrBlank() || !it.displayNumber.isNullOrBlank() }
            .distinctBy { it.id }
            .sortedWith(compareBy<TifChannelRow, String?>(NATURAL_NUMBER_ORDER) { it.displayNumber }.thenBy { it.displayName.orEmpty().lowercase() })
        if (visible.isEmpty()) return SystemTvResult.Ready(emptyList(), emptyMap())

        val programs = try {
            reader.programs(visible.map { it.id }, now - 1, now + UPCOMING_WINDOW_MS)
        } catch (_: SecurityException) {
            return SystemTvResult.NeedsPermission
        } catch (_: Exception) {
            emptyList() // Channels are still useful without program data.
        }
        val byChannel = programs.groupBy { it.channelId }
        val upcoming = linkedMapOf<String, Program>()
        val channels = visible.map { row ->
            val picked = byChannel[row.id].orEmpty().let { selectPrograms(it, now) }
            val id = row.id.toString()
            picked.second?.let { upcoming[id] = it.toProgram() }
            Channel(
                id = id,
                name = row.displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: row.displayNumber.orEmpty(),
                number = row.displayNumber?.trim()?.ifEmpty { null },
                imageUri = null,
                current = picked.first?.toProgram(live = true),
            )
        }
        return SystemTvResult.Ready(channels, upcoming)
    }

    internal companion object {
        /** Returns (airing now, next upcoming within the window) for one channel's rows. */
        fun selectPrograms(rows: List<TifProgramRow>, now: Long): Pair<TifProgramRow?, TifProgramRow?> {
            val valid = rows.filter { it.endMs > it.startMs }
            val current = valid.filter { it.startMs <= now && it.endMs > now }.maxByOrNull { it.startMs }
            val next = valid.filter { it.startMs > now && it.startMs <= now + UPCOMING_WINDOW_MS }.minByOrNull { it.startMs }
            return current to next
        }

        private fun TifProgramRow.toProgram(live: Boolean = false) = Program(
            id = "tif-$channelId-$startMs",
            name = title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled program",
            episodeTitle = episodeTitle?.trim()?.ifEmpty { null },
            overview = description?.trim()?.ifEmpty { null },
            start = Instant.ofEpochMilli(startMs),
            end = Instant.ofEpochMilli(endMs),
            isLive = live,
        )

        /** Numeric-aware ordering so "2" < "10" and "7.1" < "7.10". Blank / missing numbers sort last. */
        val NATURAL_NUMBER_ORDER = Comparator<String?> { a, b ->
            when {
                a.isNullOrBlank() && b.isNullOrBlank() -> 0
                a.isNullOrBlank() -> 1
                b.isNullOrBlank() -> -1
                else -> naturalCompare(a.trim(), b.trim())
            }
        }

        private fun naturalCompare(a: String, b: String): Int {
            val left = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
            val right = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').padStart(1, '0').let { xs ->
                        y.trimStart('0').padStart(1, '0').let { ys ->
                            if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
                        }
                    }
                } else x.compareTo(y, ignoreCase = true)
                if (cmp != 0) return cmp
            }
            return left.size.compareTo(right.size)
        }
    }
}

internal fun Cursor.string(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.long(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

internal fun parseChannelRows(cursor: Cursor, limit: Int = MAX_CHANNEL_ROWS): List<TifChannelRow> {
    val rows = ArrayList<TifChannelRow>()
    while (rows.size < limit && cursor.moveToNext()) {
        val id = cursor.long(TvContract.Channels._ID) ?: continue
        rows += TifChannelRow(
            id = id,
            displayNumber = cursor.string(TvContract.Channels.COLUMN_DISPLAY_NUMBER),
            displayName = cursor.string(TvContract.Channels.COLUMN_DISPLAY_NAME),
            packageName = cursor.string(TvContract.Channels.COLUMN_PACKAGE_NAME),
            type = cursor.string(TvContract.Channels.COLUMN_TYPE),
            browsable = cursor.long(TvContract.Channels.COLUMN_BROWSABLE) == 1L,
        )
    }
    return rows
}

internal fun parseProgramRows(cursor: Cursor, limit: Int = MAX_PROGRAM_ROWS): List<TifProgramRow> {
    val rows = ArrayList<TifProgramRow>()
    while (rows.size < limit && cursor.moveToNext()) {
        val channelId = cursor.long(TvContract.Programs.COLUMN_CHANNEL_ID) ?: continue
        val start = cursor.long(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS) ?: continue
        val end = cursor.long(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS) ?: continue
        rows += TifProgramRow(
            channelId = channelId,
            title = cursor.string(TvContract.Programs.COLUMN_TITLE),
            episodeTitle = cursor.string(TvContract.Programs.COLUMN_EPISODE_TITLE),
            description = cursor.string(TvContract.Programs.COLUMN_SHORT_DESCRIPTION)
                ?: cursor.string(TvContract.Programs.COLUMN_LONG_DESCRIPTION),
            startMs = start,
            endMs = end,
        )
    }
    return rows
}

/** Thin seam over [ContentResolver.query] so the exact TV provider calls can be verified in JVM tests. */
internal fun interface TvQuery {
    fun query(uri: Uri?, projection: Array<String>, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor?
}

internal class ContentResolverTvListingsReader(
    private val tvQuery: TvQuery,
    private val channelsUri: () -> Uri? = { TvContract.Channels.CONTENT_URI },
    private val programsUri: (Long, Long, Long) -> Uri? = { channelId, fromMs, toMs ->
        TvContract.buildProgramsUriForChannel(channelId, fromMs, toMs)
    },
) : TvListingsReader {
    constructor(resolver: ContentResolver) : this(
        TvQuery { uri, projection, selection, args, sort -> uri?.let { resolver.query(it, projection, selection, args, sort) } },
    )

    override fun channels(): List<TifChannelRow> {
        // No SQL selection: the table is small and the TV provider may restrict selections for apps.
        val projection = arrayOf(
            TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
        )
        val cursor = tvQuery.query(channelsUri(), projection, null, null, null) ?: return emptyList()
        return cursor.use { parseChannelRows(it) }
    }

    override fun programs(channelIds: List<Long>, fromMs: Long, toMs: Long): List<TifProgramRow> {
        // Android's TvProvider forbids SQL selection and sortOrder for apps without ACCESS_ALL_EPG_DATA.
        // TvContract.buildProgramsUriForChannel(id, start, end) is the official READ_TV_LISTINGS-safe API,
        // so each channel is queried individually with a null selection and null sortOrder.
        val projection = arrayOf(
            TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_EPISODE_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_LONG_DESCRIPTION,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
        )
        val rows = ArrayList<TifProgramRow>()
        for (channelId in channelIds) {
            val remaining = MAX_PROGRAM_ROWS - rows.size
            if (remaining <= 0) break
            val cursor = tvQuery.query(programsUri(channelId, fromMs, toMs), projection, null, null, null) ?: continue
            cursor.use { rows += parseProgramRows(it, limit = remaining) }
        }
        return rows
    }
}

/** Channel logo bytes (≤2 MiB) from the TV provider, or `null` when absent / too large / unreadable. */
internal fun readSystemChannelLogo(resolver: ContentResolver, channelId: Long): ByteArray? = runCatching {
    resolver.openInputStream(TvContract.buildChannelLogoUri(channelId))?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_LOGO_READ) return@runCatching null
        }
        out.toByteArray().takeIf { it.isNotEmpty() }
    }
}.getOrNull()

internal fun hasTvListingsPermission(context: Context) =
    context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

internal fun systemTvSource(context: Context) =
    SystemTvChannelsSource(ContentResolverTvListingsReader(context.applicationContext.contentResolver))
