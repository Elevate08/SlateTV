package com.stasisstate.slatetv.plugin.jellyfin

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible trampoline that Slate TV launches (by explicit component) to tune a System Live Channel.
 * It only ever opens `content://android.media.tv/channel/<id>`, so the host's launch allowlist is not widened.
 */
class TuneChannelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.let { extras ->
            when (val raw = extras.get(EXTRA_TIF_CHANNEL_ID)) {
                is Long -> raw
                is Int -> raw.toLong()
                else -> extras.getString(CHANNEL_ID)?.toLongOrNull()
            }
        }
        if (id == null || !tuneSystemChannel(this, id)) {
            Toast.makeText(this, "Could not open Live Channels for this channel.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
