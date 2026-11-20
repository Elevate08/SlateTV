package com.stasisstate.slatetv.connector.livetv

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.stasisstate.slatetv.connector.livetv.LiveTvSettingsUi as Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val REQUEST_TV_LISTINGS = 4107
private val MAX_CHANNEL_CHOICES = listOf(6, 10, 12, 15, 20)

class LiveTvSettingsActivity : LiveTvBaseActivity() {
    private lateinit var store: LiveTvConfigStore
    private lateinit var config: LiveTvConfig

    private lateinit var bannerState: TextView
    private lateinit var bannerDetail: TextView
    private lateinit var permissionRow: Row
    private lateinit var grantRow: Row
    private lateinit var adbHint: TextView
    private lateinit var limitRow: Row
    private lateinit var tapRow: Row

    private var systemChannelCount: Int? = null
    private var permissionDenied = false
    private var message = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LiveTvConfigStore(applicationContext)
        config = store.load()

        val content = column().apply {
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(dp(56), dp(32), dp(56), dp(40))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BACKGROUND)
            addView(content)
        })

        content.addView(text("Live TV & Guide", 32f, Ui.TEXT, bold = true))
        content.addView(text("Channels and the program guide come from Android TV System Live Channels. No sign-in needed.", 17f, Ui.MUTED).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val banner = column().apply {
            background = cardBackground(Ui.CARD, Ui.CARD_BORDER)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        bannerState = text("", 19f, Ui.TEXT, bold = true)
        bannerDetail = text("", 16f, Ui.MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        banner.addView(bannerState)
        banner.addView(bannerDetail)
        content.addView(banner, LinearLayout.LayoutParams(-1, -2))

        content.addView(sectionHeader("SYSTEM LIVE CHANNELS"))
        permissionRow = row("Live TV listings access", "") {}
        permissionRow.view.isFocusable = false
        content.addView(permissionRow.view, rowParams())
        grantRow = row("Grant Live TV Listings Access", null, centered = true) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(READ_TV_LISTINGS), REQUEST_TV_LISTINGS)
        }
        content.addView(grantRow.view, rowParams())
        adbHint = text("", 15f, Ui.WARN).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextIsSelectable(false)
        }
        content.addView(adbHint)
        val checkRow = row("Check channels again", null, centered = true) {
            message = ""
            refreshSystemStatus()
        }
        content.addView(checkRow.view, rowParams())

        content.addView(sectionHeader("DISPLAY & LAUNCH"))
        limitRow = row("Max home channels", "") {
            val current = MAX_CHANNEL_CHOICES.indexOf(config.maxHomeChannels).takeIf { it >= 0 }
                ?: MAX_CHANNEL_CHOICES.indices.minBy { kotlin.math.abs(MAX_CHANNEL_CHOICES[it] - config.maxHomeChannels) }
            val next = MAX_CHANNEL_CHOICES[(current + 1) % MAX_CHANNEL_CHOICES.size]
            updateConfig { copy(maxHomeChannels = next) }
        }
        tapRow = row("Card tap", "") {
            updateConfig {
                copy(cardTapAction = if (cardTapAction == CardTapAction.OPEN_GUIDE) CardTapAction.LAUNCH_CHANNEL else CardTapAction.OPEN_GUIDE)
            }
        }
        content.addView(limitRow.view, rowParams())
        content.addView(tapRow.view, rowParams())
        content.addView(row("Open Live TV guide", null, centered = true) { guide() }.view, rowParams())

        content.addView(sectionHeader("APP CHANNELS"))
        content.addView(text(
            "Jellyfin: when the Jellyfin app is installed, its home-screen rows (Next Up, Latest Media, Watch Next) " +
                "appear through App Channels. No Jellyfin sign-in is needed in Slate TV.",
            15f, Ui.MUTED,
        ).apply { setPadding(dp(8), 0, dp(8), dp(8)) })
        content.addView(row("Customize App Channels", "Choose channels from installed apps") {
            startActivity(Intent(this, AppChannelsSettingsActivity::class.java))
        }.view, rowParams())

        refreshViews()
        (if (grantRow.view.visibility == View.VISIBLE) grantRow else checkRow).view.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
    }

    // ---------------------------------------------------------------- state

    private fun updateConfig(transform: LiveTvConfig.() -> LiveTvConfig) {
        config = config.transform()
        persist(config)
        refreshViews()
    }

    private fun persist(snapshot: LiveTvConfig) {
        persistScope.launch {
            persistLock.withLock {
                val saved = runCatching { store.save(snapshot) }.isSuccess
                if (!saved) withContext(Dispatchers.Main) { message = "Could not save settings."; refreshViews() }
            }
        }
    }

    private fun granted() = hasTvListingsPermission(this)

    private fun refreshSystemStatus() {
        if (!granted()) {
            systemChannelCount = null
            refreshViews()
            return
        }
        permissionDenied = false
        scope.launch {
            systemChannelCount = withContext(Dispatchers.IO) {
                runCatching { (systemTvSource(applicationContext).load() as? SystemTvResult.Ready)?.channels?.size }.getOrNull()
            }
            refreshViews()
        }
    }

    private fun refreshViews() {
        val (state, color) = bannerSummary()
        bannerState.text = state
        bannerState.setTextColor(color)
        bannerDetail.text = message
        bannerDetail.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE

        val hasPermission = granted()
        permissionRow.value.text = when {
            !hasPermission -> "Not granted"
            systemChannelCount == null -> "Granted · checking channels…"
            systemChannelCount == 0 -> "Granted · no channels found"
            else -> "Granted · ${systemChannelCount} channels"
        }
        permissionRow.value.setTextColor(if (hasPermission) Ui.OK else Ui.WARN)
        grantRow.view.visibility = if (hasPermission) View.GONE else View.VISIBLE
        adbHint.visibility = if (permissionDenied && !hasPermission) View.VISIBLE else View.GONE
        adbHint.text = "Android TV did not allow the prompt. From a computer, run:\n" +
            "adb shell pm grant $packageName $READ_TV_LISTINGS"

        limitRow.value.text = config.maxHomeChannels.toString()
        tapRow.value.text = when (config.cardTapAction) {
            CardTapAction.OPEN_GUIDE -> "Open the Live TV guide at the selected channel"
            CardTapAction.LAUNCH_CHANNEL -> "Tune the selected channel in Live Channels"
        }
    }

    private fun bannerSummary(): Pair<String, Int> = when {
        !granted() -> "System Live Channels · Permission needed" to Ui.WARN
        systemChannelCount == null -> "System Live Channels · Checking channels…" to Ui.MUTED
        systemChannelCount == 0 -> "System Live Channels · No channels yet. Set up channels in Live Channels." to Ui.WARN
        else -> "System Live Channels · ${systemChannelCount} channels ready" to Ui.OK
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_TV_LISTINGS) return
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permissionDenied = !ok
        message = if (ok) "Live TV listings access granted." else ""
        refreshSystemStatus()
    }

    private companion object {
        val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val persistLock = Mutex()
    }
}
