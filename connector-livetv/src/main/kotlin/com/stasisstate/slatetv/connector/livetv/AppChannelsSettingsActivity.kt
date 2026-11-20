package com.stasisstate.slatetv.connector.livetv

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.stasisstate.slatetv.connector.livetv.LiveTvSettingsUi as Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppChannelsSettingsActivity : LiveTvBaseActivity() {
    private val source by lazy { androidTvAppChannelsSource(applicationContext) }
    private val store by lazy { AppChannelsConfigStore(applicationContext) }
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private lateinit var status: TextView
    private lateinit var adbHint: TextView
    private lateinit var grantRow: Row
    private lateinit var refreshRow: Row
    private lateinit var channelsView: LinearLayout
    private var refreshJob: Job? = null
    private val channelRows = mutableMapOf<AppChannel, Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = column().apply {
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(dp(56), dp(32), dp(56), dp(40))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        content.addView(text("App Channels (Installed Apps)", 32f, Ui.TEXT, bold = true))
        content.addView(text("Choose which app channels appear on Slate TV Home.", 17f, Ui.MUTED).apply {
            setPadding(0, dp(4), 0, dp(16))
        })
        grantRow = row("Grant TV listings access", null) { requestPermission.launch(READ_TV_LISTINGS) }
        content.addView(grantRow.view, rowParams())
        adbHint = text(
            "If Android TV does not show a permission prompt, run this from a computer:\n" +
                "adb shell pm grant $packageName $READ_TV_LISTINGS",
            16f, Ui.WARN,
        )
        content.addView(adbHint, rowParams())
        refreshRow = row("Refresh channels", null, onClick = ::refresh)
        content.addView(refreshRow.view, rowParams())
        status = text("", 16f, Ui.MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        channelsView = column()
        content.addView(channelsView)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        refreshJob?.cancel()
        status.visibility = View.VISIBLE
        val granted = hasTvListingsPermission(this)
        val grantHadFocus = grantRow.view.hasFocus()
        grantRow.view.visibility = if (granted) View.GONE else View.VISIBLE
        adbHint.visibility = grantRow.view.visibility
        refreshRow.view.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) {
            channelsView.removeAllViews()
            channelRows.clear()
            status.text = "Allow TV listings access to see channels published by installed apps."
            grantRow.view.requestFocus()
            return
        }
        if (grantHadFocus || currentFocus == null) refreshRow.view.requestFocus()

        status.text = "Checking channels…"
        refreshJob = scope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { source.discover() }.getOrNull()?.associateWith { channel ->
                    runCatching { source.programs(channel).itemCount }.getOrNull()
                }
            }
            if (!hasTvListingsPermission(this@AppChannelsSettingsActivity)) {
                refresh()
                return@launch
            }
            if (counts == null) {
                status.text = "Could not read app channels. Select Refresh channels to try again."
                return@launch
            }
            renderChannels(counts)
        }
    }

    private fun renderChannels(counts: Map<AppChannel, Int?>) {
        val focusedKey = currentFocus?.tag
        channelsView.removeAllViews()
        channelRows.clear()
        val discovered = counts.keys.toList()
        val watchNextHasItems = counts[AppChannel.WatchNext]?.let { it > 0 }
        fun updateRows(config: AppChannelsConfig) {
            val enabled = config.enabledKeys(discovered, watchNextHasItems)
            for ((channel, row) in channelRows) {
                val isEnabled = channel.key in enabled
                val count = counts[channel]
                val itemLabel = when (count) {
                    null -> "Item count unavailable"
                    1 -> "1 item"
                    else -> "$count items"
                }
                row.value.text = "${if (isEnabled) "Enabled" else "Off"} · $itemLabel"
                row.value.setTextColor(if (isEnabled) Ui.OK else Ui.MUTED)
            }
        }
        fun addChannel(channel: AppChannel, title: String) {
            val toggle = row(title, "") {
                updateRows(store.toggle(channel, discovered, watchNextHasItems))
            }
            toggle.view.tag = channel.key
            channelsView.addView(toggle.view, rowParams())
            channelRows[channel] = toggle
        }

        addChannel(AppChannel.WatchNext, AppChannel.WatchNext.shelfTitle)
        val previews = discovered.filterIsInstance<AppChannel.Preview>()
        for ((_, channels) in previews.groupBy { it.ownerPackage }) {
            channelsView.addView(sectionHeader(channels.first().appLabel))
            channels.forEach { addChannel(it, it.displayName) }
        }
        status.text = if (previews.isEmpty()) "No app channels are currently available." else ""
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        updateRows(store.load())
        if (focusedKey != null) {
            val focusedRow = channelRows.entries.firstOrNull { it.key.key == focusedKey }?.value
            if (focusedRow?.view?.requestFocus() != true) refreshRow.view.requestFocus()
        }
    }
}
