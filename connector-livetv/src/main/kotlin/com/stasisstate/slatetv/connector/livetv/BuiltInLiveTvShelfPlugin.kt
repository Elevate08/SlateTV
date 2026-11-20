package com.stasisstate.slatetv.connector.livetv

import android.content.Context
import android.media.tv.TvContract
import android.os.SystemClock
import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Live TV & Guide shelf running in-process inside the host app. Channels come only from Android TV System
 * Live Channels (no login). Jellyfin content is surfaced separately, with no sign-in, through App Channels.
 */
class BuiltInLiveTvShelfPlugin(private val context: Context) : ShelfPlugin {
    private val appContext = context.applicationContext ?: context
    private val logoCache by lazy { LiveTvLogoCache(File(appContext.cacheDir, LIVE_TV_LOGO_DIR)) }

    override val metadata = ShelfPluginMetadata(
        id = PLUGIN_ID,
        name = "Live TV & Guide",
        description = "Live TV channels and program guide from System Live Channels",
        version = "1.0.0",
        packageName = context.packageName,
        settingsActivityClassName = LiveTvSettingsActivity::class.java.name,
        guideActivityClassName = LiveTvGuideActivity::class.java.name,
    )

    /**
     * Returns a confirmed shelf, an eligible stale success, or `null` for a transient failure (cold refresh
     * still running, timeout, network error) so the host keeps its previous shelf and retries shortly.
     */
    override suspend fun fetchShelf(): ShelfData? {
        val config = withContext(Dispatchers.IO) { LiveTvConfigStore(appContext).load() }
        if (!hasTvListingsPermission(appContext)) return unavailable(NEEDS_PERMISSION)
        return shelfCache.getFetch(config, ::fetchSystemShelf)
    }

    private suspend fun fetchSystemShelf(config: LiveTvConfig): ShelfFetch {
        if (!hasTvListingsPermission(appContext)) return ShelfFetch(unavailable(NEEDS_PERMISSION))
        val resolver = appContext.contentResolver
        val result = withContext(Dispatchers.IO) { SystemTvChannelsSource(ContentResolverTvListingsReader(resolver)).load() }
        val ready = when (result) {
            SystemTvResult.NeedsPermission -> return ShelfFetch(unavailable(NEEDS_PERMISSION))
            is SystemTvResult.Ready -> result
        }
        val channels = ready.channels.take(config.maxHomeChannels.coerceIn(1, 24))
        if (channels.isEmpty()) return ShelfFetch(unavailable("No Live Channels found. Set up channels in Android TV Live Channels."))
        val now = Instant.now()
        val inGuide = config.cardTapAction == CardTapAction.OPEN_GUIDE

        val proxied = proxyLogos(
            channels = channels,
            idOf = { it.id },
            rawUrlOf = { TvContract.buildChannelLogoUri(it.id.toLong())?.toString() },
            semaphore = logoSemaphore,
        ) { channel, rawUrl ->
            logoCache.publishSystemLogo(rawUrl) { readSystemChannelLogo(resolver, channel.id.toLong()) }
        }
        val items = channels.map { channel ->
            systemCard(channel, proxied.logos[channel.id], inGuide, appContext.packageName, GUIDE_ACTIVITY, now)
        }.distinctBy(ShelfCard::id)
        return ShelfFetch(shelf(items), hasMissingLogos = proxied.hasMissingLogos)
    }

    private fun shelf(items: List<ShelfCard>) =
        ShelfData(true, "Live TV · On Now", items, guideActivityClassName = GUIDE_ACTIVITY)

    private fun unavailable(reason: String) = ShelfData(false, "Live TV", emptyList(), reason)

    companion object {
        const val PLUGIN_ID = "livetv"
        const val LEGACY_EXTERNAL_ID = "com.stasisstate.slatetv.plugin.jellyfin.livetv"
        const val EXTRA_TIF_CHANNEL_ID = "tif_channel_id"
        const val EXTRA_CHANNEL_ID = "channel_id"

        /** TV Provider `preview_program._id` of an installed app's home-screen channel item. */
        const val EXTRA_PREVIEW_PROGRAM_ID = "preview_program_id"

        /** TV Provider `watch_next_program._id` of an installed app's Watch Next item. */
        const val EXTRA_WATCH_NEXT_PROGRAM_ID = "watch_next_program_id"

        /** Package that published the preview / watch-next row; re-verified against the TV Provider at click time. */
        const val EXTRA_APP_CHANNEL_PACKAGE = "app_channel_package"

        private const val NEEDS_PERMISSION = "Grant Live TV listings access in Settings."
        private val GUIDE_ACTIVITY: String = LiveTvGuideActivity::class.java.name
        private val logoSemaphore = Semaphore(LOGO_FETCH_PERMITS)
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val shelfCache = LiveTvShelfCache(refreshScope, SystemClock::elapsedRealtime)
    }
}

/** `content://android.media.tv/channel/<id>`: the canonical TV Provider channel URI (what `TvContract.buildChannelUri` returns). */
internal fun channelUriString(channelId: Long) = "content://android.media.tv/channel/$channelId"

private fun baseCard(channel: Channel, logoUri: String?, now: Instant) = ShelfCard(
    id = channel.id.take(128),
    title = channel.name.take(120),
    subtitle = listOfNotNull(channel.current?.title, channel.current?.remainingTime(now)).joinToString(" · ").take(160)
        .ifBlank { null },
    channelNumber = channel.number?.take(16),
    badge = if (channel.current?.isLive == true) "LIVE" else null,
    imageUri = logoUri,
    progressPercent = channel.current?.progressPercent(now) ?: -1,
    startTimeEpochMs = channel.current?.start?.toEpochMilli() ?: 0,
    endTimeEpochMs = channel.current?.end?.toEpochMilli() ?: 0,
)

/**
 * Card for a System Live Channel. In "tune" mode it carries the TV Provider channel URI plus
 * [BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID] so the host can call [LiveTvChannelTuner.tuneSystemChannel] in-process.
 */
internal fun systemCard(
    channel: Channel,
    logoUri: String?,
    inGuide: Boolean,
    packageName: String,
    guideActivity: String,
    now: Instant,
): ShelfCard {
    val base = baseCard(channel, logoUri, now)
    val tifId = channel.id.toLongOrNull()?.takeIf { it >= 0 }
    return if (inGuide || tifId == null) {
        base.copy(
            launchPackageName = packageName,
            launchActivityClassName = guideActivity,
            launchExtras = mapOf(BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID to channel.id.take(2048)),
        )
    } else {
        base.copy(
            launchUri = channelUriString(tifId),
            launchExtras = mapOf(BuiltInLiveTvShelfPlugin.EXTRA_TIF_CHANNEL_ID to tifId),
        )
    }
}
