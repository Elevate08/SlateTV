package com.stasisstate.slatetv.connector.livetv

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvContract.PreviewPrograms
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri

internal sealed interface AppChannel {
    val key: String
    val shelfTitle: String

    data class Preview(
        val id: Long,
        val ownerPackage: String,
        val appLabel: String,
        val displayName: String,
        val browsable: Boolean,
    ) : AppChannel {
        override val key = "preview:$ownerPackage:$id"
        override val shelfTitle = "$appLabel · $displayName"
    }

    data object WatchNext : AppChannel {
        override val key = "watch_next"
        override val shelfTitle = "Watch Next"
    }
}

internal data class AppProgramRow(
    val id: Long,
    val ownerPackage: String,
    val title: String? = null,
    val episodeTitle: String? = null,
    val description: String? = null,
    val posterUri: String? = null,
    val thumbnailUri: String? = null,
    val durationMs: Long? = null,
    val playbackPositionMs: Long? = null,
    val live: Boolean = false,
    val weight: Long = 0,
    val browsable: Boolean? = null,
    val lastEngagementMs: Long = 0,
)

internal data class AppChannelPrograms(val itemCount: Int, val items: List<AppProgramRow>)

internal interface AppChannelsReader {
    fun channels(): List<TifChannelRow>
    fun previewPrograms(channelId: Long): List<AppProgramRow>
    fun watchNextPrograms(): List<AppProgramRow>
    fun appLabel(packageName: String): String
}

internal class AndroidTvAppChannelsSource(
    private val reader: AppChannelsReader,
    private val selfPackage: String,
) {
    fun discover(): List<AppChannel> {
        val labels = mutableMapOf<String, String>()
        val preview = reader.channels().mapNotNull { row ->
            if (row.type != TvContract.Channels.TYPE_PREVIEW || row.id < 0) return@mapNotNull null
            val owner = row.packageName?.takeIf(::isExternalPackage) ?: return@mapNotNull null
            val name = cleanAppChannelText(row.displayName, 120) ?: return@mapNotNull null
            val label = labels.getOrPut(owner) { cleanAppChannelText(reader.appLabel(owner), 80) ?: owner }
            AppChannel.Preview(row.id, owner, label, name, row.browsable)
        }.distinctBy(AppChannel.Preview::key).sortedWith(
            compareBy<AppChannel.Preview, String>(String.CASE_INSENSITIVE_ORDER) { it.appLabel }
                .thenBy { it.ownerPackage }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy { it.id },
        )
        return preview + AppChannel.WatchNext
    }

    fun programs(channel: AppChannel): AppChannelPrograms {
        val rows = when (channel) {
            is AppChannel.Preview -> reader.previewPrograms(channel.id)
            AppChannel.WatchNext -> reader.watchNextPrograms()
        }.filter { row ->
            row.id >= 0 && row.browsable != false && isExternalPackage(row.ownerPackage) &&
                (channel !is AppChannel.Preview || row.ownerPackage == channel.ownerPackage)
        }.distinctBy(AppProgramRow::id)
        val ordered = when (channel) {
            is AppChannel.Preview -> rows.sortedWith(compareByDescending(AppProgramRow::weight).thenBy(AppProgramRow::id))
            AppChannel.WatchNext -> rows.sortedWith(
                compareByDescending(AppProgramRow::lastEngagementMs).thenByDescending(AppProgramRow::id),
            )
        }
        return AppChannelPrograms(rows.size, ordered.take(MAX_APP_CHANNEL_ITEMS))
    }

    private fun isExternalPackage(packageName: String): Boolean =
        packageName != selfPackage && LiveTvChannelTuner.isValidPackageName(packageName)
}

internal fun cleanAppChannelText(text: String?, maxLength: Int): String? =
    text?.filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
        ?.trim()?.take(maxLength)?.trimEnd()?.ifEmpty { null }

internal class ContentResolverAppChannelsReader(
    private val tvQuery: TvQuery,
    private val labelFor: (String) -> String,
    private val channelsUri: () -> Uri? = { TvContract.Channels.CONTENT_URI },
    private val previewUri: (Long) -> Uri? = { TvContract.buildPreviewProgramsUriForChannel(it) },
    private val watchNextUri: () -> Uri? = { WatchNextPrograms.CONTENT_URI },
) : AppChannelsReader {
    override fun channels(): List<TifChannelRow> = tvQuery.query(
        channelsUri(),
        arrayOf(
            TvContract.Channels._ID, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_BROWSABLE,
        ),
        null, null, null,
    )?.use { parseChannelRows(it) }.orEmpty()

    override fun previewPrograms(channelId: Long): List<AppProgramRow> =
        readPrograms(previewUri(channelId), PROGRAM_COLUMNS + PreviewPrograms.COLUMN_WEIGHT)

    override fun watchNextPrograms(): List<AppProgramRow> =
        readPrograms(watchNextUri(), PROGRAM_COLUMNS + WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)

    override fun appLabel(packageName: String): String = labelFor(packageName)

    private fun readPrograms(uri: Uri?, projection: Array<String>): List<AppProgramRow> =
        tvQuery.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (size < MAX_APP_PROGRAM_ROWS && cursor.moveToNext()) cursor.appProgramRow()?.let(::add)
            }
        }.orEmpty()

    private fun Cursor.appProgramRow(): AppProgramRow? {
        val id = long(PreviewPrograms._ID) ?: return null
        val owner = string(PreviewPrograms.COLUMN_PACKAGE_NAME) ?: return null
        return AppProgramRow(
            id = id,
            ownerPackage = owner,
            title = string(PreviewPrograms.COLUMN_TITLE),
            episodeTitle = string(PreviewPrograms.COLUMN_EPISODE_TITLE),
            description = string(PreviewPrograms.COLUMN_SHORT_DESCRIPTION),
            posterUri = string(PreviewPrograms.COLUMN_POSTER_ART_URI),
            thumbnailUri = string(PreviewPrograms.COLUMN_THUMBNAIL_URI),
            durationMs = long(PreviewPrograms.COLUMN_DURATION_MILLIS),
            playbackPositionMs = long(PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
            live = long(PreviewPrograms.COLUMN_LIVE) == 1L,
            weight = long(PreviewPrograms.COLUMN_WEIGHT) ?: 0,
            browsable = long(PreviewPrograms.COLUMN_BROWSABLE)?.let { it != 0L },
            lastEngagementMs = long(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) ?: 0,
        )
    }

    private companion object {
        // Preview and Watch Next share these columns. Weight exists only on preview programs.
        val PROGRAM_COLUMNS = arrayOf(
            PreviewPrograms._ID, PreviewPrograms.COLUMN_PACKAGE_NAME, PreviewPrograms.COLUMN_TITLE,
            PreviewPrograms.COLUMN_EPISODE_TITLE, PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            PreviewPrograms.COLUMN_POSTER_ART_URI, PreviewPrograms.COLUMN_THUMBNAIL_URI,
            PreviewPrograms.COLUMN_DURATION_MILLIS, PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            PreviewPrograms.COLUMN_LIVE, PreviewPrograms.COLUMN_BROWSABLE,
        )
    }
}

internal fun androidTvAppChannelsSource(context: Context): AndroidTvAppChannelsSource {
    val resolver = context.contentResolver
    val packages = context.packageManager
    val reader = ContentResolverAppChannelsReader(
        TvQuery { uri, projection, selection, args, sort ->
            uri?.let { resolver.query(it, projection, selection, args, sort) }
        },
        labelFor = { packageName ->
            @Suppress("DEPRECATION")
            runCatching { packages.getApplicationInfo(packageName, 0).loadLabel(packages).toString() }
                .getOrDefault(packageName)
        },
    )
    return AndroidTvAppChannelsSource(reader, context.packageName)
}

private const val MAX_APP_CHANNEL_ITEMS = 20
internal const val MAX_APP_PROGRAM_ROWS = 200
