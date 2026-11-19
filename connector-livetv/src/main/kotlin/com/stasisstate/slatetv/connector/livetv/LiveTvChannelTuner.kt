package com.stasisstate.slatetv.connector.livetv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri

/** In-process tuning of System Live Channels; no trampoline activity or IPC involved. */
object LiveTvChannelTuner {
    /**
     * Tunes a system channel in Live Channels (or the channel's own input app). Returns true when an
     * activity was started. Only ever fires `content://android.media.tv/channel/<id>` for a non-negative id.
     */
    fun tuneSystemChannel(context: Context, channelId: Long): Boolean {
        if (channelId < 0) return false
        val uri: Uri = TvContract.buildChannelUri(channelId)
        fun start(intent: Intent) = try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (start(Intent(Intent.ACTION_VIEW, uri))) return true
        if (start(Intent(Intent.ACTION_VIEW, uri).setPackage(LIVE_CHANNELS_PACKAGE))) return true
        val inputPackage = runCatching {
            context.contentResolver.query(
                uri, arrayOf(TvContract.Channels.COLUMN_PACKAGE_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.string(TvContract.Channels.COLUMN_PACKAGE_NAME) else null }
        }.getOrNull()
        val launch = inputPackage?.let {
            context.packageManager.getLeanbackLaunchIntentForPackage(it) ?: context.packageManager.getLaunchIntentForPackage(it)
        }
        return launch != null && start(launch)
    }
}
