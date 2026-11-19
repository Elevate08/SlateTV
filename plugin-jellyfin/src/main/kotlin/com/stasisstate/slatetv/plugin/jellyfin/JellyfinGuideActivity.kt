package com.stasisstate.slatetv.plugin.jellyfin

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class JellyfinGuideActivity : JellyfinTvActivity() {
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var synopsis: TextView
    private var load: Job? = null
    private var selectedChannel: String? = null
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedChannel = intent.getStringExtra(CHANNEL_ID)
        val root = rootColumn()
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(label("Live TV guide", 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Refresh") { refresh() })
        header.addView(button("Settings") { settings() })
        root.addView(header)
        status = label("")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(status)
        rows = column()
        val scroll = ScrollView(this).apply { addView(rows); isFocusable = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        synopsis = label("", 18f).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(synopsis)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        load?.cancel()
        load = scope.launch {
            status.visibility = View.VISIBLE
            status.text = "Loading Jellyfin Live TV…"
            try {
                val config = JellyfinConfigStore(this@JellyfinGuideActivity).load()
                if (config.apiKey.isBlank() || config.serverUrl.isBlank()) {
                    empty("Open Settings to connect your Jellyfin server.")
                    return@launch
                }
                val api = JellyfinApiClient(config)
                if (!api.liveTvEnabled()) {
                    empty("Live TV is unavailable on this Jellyfin server.")
                    return@launch
                }
                val userId = config.userId.ifBlank { api.detectUserId() }
                val channels = api.guideChannels(userId)
                if (channels.isEmpty()) {
                    empty("No Live TV channels found.")
                    return@launch
                }
                val now = Instant.now()
                render(channels, emptyMap(), now)
                try {
                    val next = buildMap {
                        for (group in channels.chunked(20)) {
                            putAll(api.upNext(userId, group.map(Channel::id), now))
                        }
                    }
                    render(channels, next, Instant.now())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status.visibility = View.VISIBLE
                    status.text = "Up Next is temporarily unavailable."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                empty("Could not load Live TV. Check Settings or select Refresh.")
            }
        }
    }

    private fun empty(message: String) {
        rows.removeAllViews()
        synopsis.text = ""
        status.text = message
    }

    private fun render(channels: List<Channel>, next: Map<String, Program>, now: Instant) {
        val focusChannel = currentFocus?.parent === rows || rows.childCount == 0
        rows.removeAllViews()
        status.visibility = View.GONE
        var target: View? = null
        for (channel in channels) {
            val current = channel.current
            val upcoming = next[channel.id]
            val row = LinearLayout(this).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = focusBackground()
                isFocusable = true
                setOnClickListener { launchJellyfin() }
                setOnFocusChangeListener { _, focused ->
                    if (focused) {
                        selectedChannel = channel.id
                        synopsis.text = current?.overview ?: upcoming?.overview ?: "No program synopsis available."
                    }
                }
            }
            val station = listOfNotNull(channel.number, channel.name).joinToString("  ")
            row.addView(label(station, 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
            val onNow = column().apply {
                addView(label("On now · ${current?.title ?: "No program data"}"))
                current?.remainingTime(now)?.let { addView(label(it, 15f)) }
                val progress = current?.progressPercent(now) ?: -1
                if (progress >= 0) addView(ProgressBar(this@JellyfinGuideActivity, null,
                    android.R.attr.progressBarStyleHorizontal).apply { this.progress = progress })
            }
            row.addView(onNow, LinearLayout.LayoutParams(0, -2, 2f).apply { marginEnd = dp(16) })
            val nextLabel = upcoming?.let { "Up next · ${timeFormat.format(it.start)}\n${it.title}" } ?: "Up next · —"
            row.addView(label(nextLabel), LinearLayout.LayoutParams(0, -2, 2f))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            if (channel.id == selectedChannel) target = row
        }
        if (focusChannel) (target ?: rows.getChildAt(0))?.requestFocus()
    }
}
