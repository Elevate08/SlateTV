package com.stasisstate.slatetv.connector.livetv

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** Slate TV dark architectural palette for the 10-foot settings menu. */
private object Ui {
    const val BACKGROUND = 0xff0a0d12.toInt()
    const val CARD = 0xff141923.toInt()
    const val CARD_BORDER = 0xff1f2735.toInt()
    const val CARD_FOCUS = 0xff1d2635.toInt()
    const val CARD_SELECTED_BORDER = 0xff2f8aa0.toInt()
    const val ACCENT = 0xff78dcf0.toInt()
    const val TEXT = 0xffebf4ff.toInt()
    const val MUTED = 0xff8fa0b8.toInt()
    const val OK = 0xff6fe3a1.toInt()
    const val WARN = 0xffffc66d.toInt()
    const val BAD = 0xffff7b72.toInt()
}

private const val REQUEST_TV_LISTINGS = 4107
private val MAX_CHANNEL_CHOICES = listOf(6, 10, 15, 20)

class LiveTvSettingsActivity : LiveTvBaseActivity() {
    private class Row(val view: LinearLayout, val title: TextView, val value: TextView)

    private lateinit var store: LiveTvConfigStore
    private lateinit var config: LiveTvConfig

    private lateinit var bannerState: TextView
    private lateinit var bannerDetail: TextView
    private lateinit var banner: LinearLayout
    private lateinit var systemSourceRow: Row
    private lateinit var jellyfinSourceRow: Row
    private lateinit var systemSection: LinearLayout
    private lateinit var jellyfinSection: LinearLayout
    private lateinit var permissionRow: Row
    private lateinit var grantRow: Row
    private lateinit var adbHint: TextView
    private lateinit var serverRow: Row
    private lateinit var keyRow: Row
    private lateinit var userRow: Row
    private lateinit var tlsRow: Row
    private lateinit var favoritesRow: Row
    private lateinit var limitRow: Row
    private lateinit var tapRow: Row

    private var systemChannelCount: Int? = null
    private var permissionDenied = false
    private var serverStatus: String? = null
    private var message = ""
    private var request: Job? = null
    private var quickConnectJob: Job? = null
    private var discoveryJob: Job? = null
    private var pairing: LanPairingServer? = null
    private var activeDialog: Dialog? = null
    private var pairingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
        content.addView(text("Choose where Slate TV gets your channels and program guide.", 17f, Ui.MUTED).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        banner = column().apply {
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

        content.addView(sectionHeader("LIVE TV SOURCE"))
        systemSourceRow = row("System Live Channels", "No login. Uses the channels already set up in Android TV Live Channels.") {
            selectSource(LiveTvSourceMode.SYSTEM_LIVE_CHANNELS)
        }
        jellyfinSourceRow = row("Jellyfin Server", "Show Live TV from your Jellyfin server.") {
            selectSource(LiveTvSourceMode.JELLYFIN_SERVER)
        }
        content.addView(systemSourceRow.view, rowParams())
        content.addView(jellyfinSourceRow.view, rowParams())

        systemSection = column()
        content.addView(systemSection)
        buildSystemSection()

        jellyfinSection = column()
        content.addView(jellyfinSection)
        buildJellyfinSection()

        content.addView(sectionHeader("DISPLAY & LAUNCH"))
        favoritesRow = row("Favorites only", "") { updateConfig { copy(favoritesOnly = !favoritesOnly) } }
        limitRow = row("Max home channels", "") {
            val next = MAX_CHANNEL_CHOICES[(MAX_CHANNEL_CHOICES.indexOf(config.maxHomeChannels) + 1) % MAX_CHANNEL_CHOICES.size]
            updateConfig { copy(maxHomeChannels = next) }
        }
        tapRow = row("Card tap", "") {
            updateConfig {
                copy(cardTapAction = if (cardTapAction == CardTapAction.OPEN_GUIDE) CardTapAction.LAUNCH_JELLYFIN else CardTapAction.OPEN_GUIDE)
            }
        }
        content.addView(favoritesRow.view, rowParams())
        content.addView(limitRow.view, rowParams())
        content.addView(tapRow.view, rowParams())
        content.addView(row("Open Live TV guide", null, centered = true) { guide() }.view, rowParams())

        refreshViews()
        systemSourceRow.view.requestFocus()
    }

    private fun buildSystemSection() {
        systemSection.addView(sectionHeader("SYSTEM LIVE CHANNELS"))
        permissionRow = row("Live TV listings access", "") {}
        permissionRow.view.isFocusable = false
        systemSection.addView(permissionRow.view, rowParams())
        grantRow = row("Grant Live TV Listings Access", null, centered = true) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(READ_TV_LISTINGS), REQUEST_TV_LISTINGS)
        }
        systemSection.addView(grantRow.view, rowParams())
        adbHint = text("", 15f, Ui.WARN).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextIsSelectable(false)
        }
        systemSection.addView(adbHint)
        systemSection.addView(row("Check channels again", null, centered = true) {
            message = ""
            refreshSystemStatus()
        }.view, rowParams())
    }

    private fun buildJellyfinSection() {
        jellyfinSection.addView(sectionHeader("JELLYFIN SERVER"))
        serverRow = row("Server address", "") {
            editDialog(
                title = "Server address",
                hint = "https://tv.example.com",
                initial = config.serverUrl,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                maxLength = 1500,
                validate = { if (it.isNotEmpty() && parseServerUrl(it) == null) "Enter a valid URL such as https://tv.example.com" else null },
            ) { value ->
                serverStatus = null
                updateConfig { copy(serverUrl = parseServerUrl(value)?.toString().orEmpty()) }
            }
        }
        jellyfinSection.addView(serverRow.view, rowParams())
        jellyfinSection.addView(row("Find servers on my network", "Looks for Jellyfin servers automatically", centered = false) {
            showDiscovery()
        }.view, rowParams())
        jellyfinSection.addView(row("Sign in with Quick Connect", "Approve a 6-digit code from the Jellyfin app on your phone", centered = false) {
            showQuickConnect()
        }.view, rowParams())
        jellyfinSection.addView(row("Test connection", null, centered = true) { testConnection() }.view, rowParams())

        jellyfinSection.addView(text("Advanced / manual", 16f, Ui.MUTED, bold = true).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        keyRow = row("API key", "") {
            editDialog(
                title = "API key",
                hint = "Paste an API key from Jellyfin Dashboard",
                initial = config.apiKey,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                maxLength = 512,
                validate = { if (it.isNotEmpty() && !validApiKey(it)) "Enter a valid API key." else null },
            ) { value -> updateConfig { copy(apiKey = value) } }
        }
        jellyfinSection.addView(keyRow.view, rowParams())
        userRow = row("User ID (optional)", "") {
            editDialog(
                title = "User ID",
                hint = "Leave blank to detect automatically",
                initial = config.userId,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                maxLength = 256,
                validate = { null },
            ) { value -> updateConfig { copy(userId = value) } }
        }
        jellyfinSection.addView(userRow.view, rowParams())
        jellyfinSection.addView(row("Auto-detect user", null, centered = true) {
            runAction { snapshot ->
                val detected = JellyfinApiClient(snapshot).detectUserId()
                updateConfig { copy(userId = detected) }
                "Jellyfin user detected."
            }
        }.view, rowParams())
        jellyfinSection.addView(row("Pair with phone", "Send server details from a phone on the same network", centered = false) {
            showPairing()
        }.view, rowParams())
        tlsRow = row("Self-signed certificate", "") { showCertificate() }
        jellyfinSection.addView(tlsRow.view, rowParams())
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
    }

    // ---------------------------------------------------------------- state

    private fun updateConfig(transform: LiveTvConfig.() -> LiveTvConfig) {
        config = config.transform().normalized()
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

    private fun selectSource(mode: LiveTvSourceMode) {
        message = ""
        updateConfig { copy(sourceMode = mode) }
        if (mode == LiveTvSourceMode.SYSTEM_LIVE_CHANNELS) refreshSystemStatus()
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
        val system = config.sourceMode == LiveTvSourceMode.SYSTEM_LIVE_CHANNELS
        systemSection.visibility = if (system) View.VISIBLE else View.GONE
        jellyfinSection.visibility = if (system) View.GONE else View.VISIBLE
        styleSource(systemSourceRow, system)
        styleSource(jellyfinSourceRow, !system)

        val (state, color) = bannerSummary(system)
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

        serverRow.value.text = config.serverUrl.ifBlank { "Not set" }
        keyRow.value.text = if (config.apiKey.isBlank()) "Not set" else "•".repeat(10) + "  saved"
        userRow.value.text = config.userId.ifBlank { "Auto-detect" }
        tlsRow.value.text = config.activePin()?.let { "Trusted · ${it.fingerprint.take(23)}…" } ?: "Not pinned · select to review this server's certificate"
        favoritesRow.view.visibility = if (system) View.GONE else View.VISIBLE
        favoritesRow.value.text = onOff(config.favoritesOnly)
        limitRow.value.text = config.maxHomeChannels.toString()
        tapRow.value.text = when {
            config.cardTapAction == CardTapAction.OPEN_GUIDE -> "Open full guide"
            system -> "Tune channel in Live Channels"
            else -> "Launch Jellyfin TV app"
        }
    }

    private fun bannerSummary(system: Boolean): Pair<String, Int> = if (system) {
        when {
            !granted() -> "System Live Channels · Permission needed" to Ui.WARN
            systemChannelCount == null -> "System Live Channels · Checking channels…" to Ui.MUTED
            systemChannelCount == 0 -> "System Live Channels · No channels yet. Set up channels in Live Channels." to Ui.WARN
            else -> "System Live Channels · ${systemChannelCount} channels ready" to Ui.OK
        }
    } else {
        when {
            config.serverUrl.isBlank() -> "Jellyfin Server · Not connected" to Ui.WARN
            config.apiKey.isBlank() -> "Jellyfin Server · Server set. Sign in with Quick Connect." to Ui.WARN
            else -> (serverStatus ?: "Jellyfin Server · Signed in. Select Test connection to verify.") to
                if (serverStatus != null) Ui.OK else Ui.MUTED
        }
    }

    private fun onOff(value: Boolean) = if (value) "On" else "Off"

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_TV_LISTINGS) return
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permissionDenied = !ok
        message = if (ok) "Live TV listings access granted." else ""
        refreshSystemStatus()
    }

    // ---------------------------------------------------------------- Jellyfin actions

    private fun runAction(action: suspend (LiveTvConfig) -> String) {
        request?.cancel()
        val snapshot = config
        message = "Working…"
        refreshViews()
        request = scope.launch {
            message = try {
                action(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                when {
                    snapshot.activePin() == null && LiveTvTofuTls.isTrustFailure(error) ->
                        "This server's certificate is not trusted. Use Self-signed certificate under Advanced to review and trust it."
                    parseServerUrl(snapshot.serverUrl) == null -> "Enter a valid Jellyfin server URL."
                    !validApiKey(snapshot.apiKey) -> "Sign in with Quick Connect or enter an API key."
                    else -> "Could not complete the request. Check the server settings and try again."
                }
            }
            refreshViews()
        }
    }

    private fun testConnection() {
        runAction { snapshot ->
            val api = JellyfinApiClient(snapshot)
            val name = api.serverName()
            val result = if (!api.liveTvEnabled()) {
                "$name · Live TV unavailable · 0 channels"
            } else {
                val count = api.channelCount(snapshot.userId.ifBlank { api.detectUserId() })
                if (count == 0) "$name · No Live TV channels" else "$name · Live TV enabled · $count channels found"
            }
            serverStatus = "Jellyfin Server · $result"
            result
        }
    }

    /** Pinned-certificate row: inspect / remove an existing pin, or review the server's certificate to pin it. */
    private fun showCertificate() {
        val base = parseServerUrl(config.serverUrl)
        val pin = config.activePin()
        if (pin != null && base != null) {
            val body = column().apply {
                addView(text("Trusted for ${base.host}:${base.port}", 18f, Ui.TEXT, bold = true))
                addView(text("SHA-256 fingerprint", 15f, Ui.MUTED).apply { setPadding(0, dp(10), 0, dp(2)) })
                addView(text(pin.fingerprint, 15f, Ui.TEXT))
            }
            lateinit var dialog: Dialog
            dialog = tvDialog(
                "Pinned certificate", body,
                "Remove" to {
                    serverStatus = null
                    message = "Pinned certificate removed."
                    updateConfig { withPin(null) }
                    dialog.dismiss()
                },
                "Close" to { dialog.dismiss() },
            )
            return
        }
        if (base == null || !base.isHttps) {
            message = "Enter an https:// server address first. Certificates only apply to HTTPS servers."
            refreshViews()
            return
        }
        message = "Checking server certificate…"
        refreshViews()
        request?.cancel()
        request = scope.launch {
            val failure = withContext(Dispatchers.IO) { LiveTvTofuTls.defaultTrustError(base) }
            when {
                failure == null -> message = "Android already trusts this server's certificate. No pinning needed."
                !LiveTvTofuTls.isTrustFailure(failure) -> message = "Could not reach the server. Check the address and try again."
                else -> {
                    val cert = withContext(Dispatchers.IO) { LiveTvTofuTls.probe(base.host, base.port) }
                    if (cert == null) {
                        message = "Could not read a certificate. The server must be on your local network."
                    } else {
                        message = ""
                        confirmCertificate(base, cert.encoded)
                    }
                }
            }
            refreshViews()
        }
    }

    private fun confirmCertificate(base: okhttp3.HttpUrl, der: ByteArray) {
        val body = column().apply {
            addView(text("Server: ${base.host}:${base.port}", 18f, Ui.TEXT, bold = true))
            addView(text("SHA-256 fingerprint", 15f, Ui.MUTED).apply { setPadding(0, dp(10), 0, dp(2)) })
            addView(text(LiveTvTofuTls.sha256Fingerprint(der), 15f, Ui.TEXT))
            addView(text("Only trust this if you recognise this server and fingerprint. Slate TV will accept this exact certificate for this server only.", 15f, Ui.WARN).apply {
                setPadding(0, dp(12), 0, 0)
            })
        }
        lateinit var dialog: Dialog
        dialog = tvDialog(
            "Trust this server?", body,
            "Trust" to {
                serverStatus = null
                message = "Certificate pinned for ${base.host}."
                updateConfig { withPin(TofuPin(serverOrigin(base), der)) }
                dialog.dismiss()
            },
            "Cancel" to { dialog.dismiss() },
        )
    }

    private fun showDiscovery() {
        discoveryJob?.cancel()
        val status = text("Searching your network…", 17f, Ui.MUTED)
        val list = column()
        val body = column().apply { addView(status); addView(list) }
        lateinit var dialog: Dialog
        fun search() {
            list.removeAllViews()
            status.text = "Searching your network…"
            discoveryJob?.cancel()
            discoveryJob = scope.launch {
                val servers = JellyfinServerDiscovery.discover()
                status.text = if (servers.isEmpty()) "No servers found. Enter the address manually." else "Select your server:"
                servers.forEach { server ->
                    list.addView(row(server.name, server.address) {
                        serverStatus = null
                        message = "Server selected. Sign in with Quick Connect."
                        updateConfig { copy(serverUrl = server.address, sourceMode = LiveTvSourceMode.JELLYFIN_SERVER) }
                        dialog.dismiss()
                    }.view, rowParams())
                }
                list.getChildAt(0)?.requestFocus()
            }
        }
        dialog = tvDialog(
            "Find servers", body,
            "Search again" to { search() },
            "Close" to { dialog.dismiss() },
        )
        dialog.setOnDismissListener { discoveryJob?.cancel(); activeDialog = null }
        search()
    }

    private fun showQuickConnect() {
        val serverUrl = config.serverUrl
        if (parseServerUrl(serverUrl) == null) {
            message = "Set your server address first, or use Find servers on my network."
            refreshViews()
            return
        }
        val code = text("······", 44f, Ui.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            setPadding(0, dp(8), 0, dp(8))
        }
        val info = text("Contacting your Jellyfin server…", 17f, Ui.MUTED)
        val body = column().apply { addView(code, LinearLayout.LayoutParams(-1, -2)); addView(info) }
        lateinit var dialog: Dialog
        dialog = tvDialog("Sign in with Quick Connect", body, "Cancel" to { dialog.dismiss() })
        dialog.setOnDismissListener { quickConnectJob?.cancel(); activeDialog = null }
        quickConnectJob?.cancel()
        quickConnectJob = scope.launch {
            try {
                val tls = config.activePin()
                val session = JellyfinApiClient.initiateQuickConnect(serverUrl, tls)
                code.text = session.code.chunked(3).joinToString(" ")
                info.text = "On your phone or computer, open Jellyfin, go to your profile, select Quick Connect, and enter this code."
                val auth = JellyfinApiClient.awaitQuickConnect(serverUrl, session.secret, tls)
                if (auth == null) {
                    info.text = "The code expired. Close this and try again."
                } else {
                    serverStatus = null
                    message = "Signed in with Quick Connect."
                    updateConfig {
                        copy(apiKey = auth.accessToken, userId = auth.userId, sourceMode = LiveTvSourceMode.JELLYFIN_SERVER)
                    }
                    dialog.dismiss()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                info.text = e.message?.takeIf { it.startsWith("Quick Connect") }
                    ?: if (config.activePin() == null && LiveTvTofuTls.isTrustFailure(e)) {
                        "This server's certificate is not trusted. Close this and use Self-signed certificate under Advanced."
                    } else {
                        "Could not reach Jellyfin. Check the server address and try again."
                    }
            }
        }
    }

    // Scans Wi-Fi and Ethernet even when a VPN is the default network. ACCESS_NETWORK_STATE is declared by the
    // host app (this library only declares READ_TV_LISTINGS); a missing grant degrades to "no LAN found".
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun showPairing() {
        stopPairing()
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lanCandidates = runCatching {
            manager.allNetworks.asSequence().filter { network ->
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .mapNotNull { it.address as? Inet4Address }
                .filter { !it.isLoopbackAddress && it.isLanOrTailscale() }
                .toList()
        }.getOrDefault(emptyList())
        val address = lanCandidates.firstOrNull { it.isSiteLocalAddress } ?: lanCandidates.firstOrNull()
        if (address == null) {
            message = "Connect your TV to a local Wi-Fi or Ethernet network to pair."
            refreshViews()
            return
        }
        lateinit var activeServer: LanPairingServer
        lateinit var dialog: Dialog
        val server = runCatching {
            LanPairingServer(address) { serverUrl, apiKey, userId ->
                runOnUiThread {
                    if (pairingDialog === dialog && pairing === activeServer) {
                        stopPairing()
                        serverStatus = null
                        message = "Pairing details received and saved."
                        updateConfig {
                            copy(serverUrl = serverUrl, apiKey = apiKey, userId = userId, sourceMode = LiveTvSourceMode.JELLYFIN_SERVER)
                        }
                    }
                }
            }
        }.getOrNull() ?: run {
            message = "Could not start LAN pairing."
            refreshViews()
            return
        }
        activeServer = server
        pairing = server
        val body = column().apply {
            addView(text("Scan on the same network · PIN ${server.pin}", 20f, Ui.TEXT, bold = true))
            addView(ImageView(this@LiveTvSettingsActivity).apply {
                setImageBitmap(QrCodeGenerator.generate(server.url))
                contentDescription = "Phone pairing QR code"
            }, LinearLayout.LayoutParams(dp(210), dp(210)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(text(server.url, 16f, Ui.MUTED))
        }
        dialog = tvDialog("Pair with phone", body, "Close" to { dialog.dismiss() })
        pairingDialog = dialog
        dialog.setOnDismissListener {
            server.close()
            if (pairing === server) pairing = null
            if (pairingDialog === dialog) pairingDialog = null
            activeDialog = null
        }
    }

    private fun stopPairing() {
        pairing?.close()
        pairing = null
        pairingDialog?.dismiss()
        pairingDialog = null
    }

    override fun onPause() {
        stopPairing()
        super.onPause()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- dialogs

    private fun tvDialog(title: String, body: View, vararg buttons: Pair<String, () -> Unit>): Dialog {
        activeDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = column().apply {
            background = cardBackground(Ui.CARD, Ui.CARD_SELECTED_BORDER, radius = 16)
            setPadding(dp(28), dp(22), dp(28), dp(22))
            addView(text(title, 24f, Ui.TEXT, bold = true).apply { setPadding(0, 0, 0, dp(12)) })
            addView(body, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(this@LiveTvSettingsActivity).apply { gravity = Gravity.END }
            buttons.forEach { (label, action) ->
                actions.addView(row(label, null, centered = true) { action() }.view,
                    LinearLayout.LayoutParams(dp(180), -2).apply { marginStart = dp(12); topMargin = dp(16) })
            }
            addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        activeDialog = dialog
        dialog.show()
        return dialog
    }

    /** Dialog with a single-line field: IME "Done" saves, keyboard opens automatically. */
    private fun editDialog(
        title: String,
        hint: String,
        initial: String,
        inputType: Int,
        maxLength: Int,
        validate: (String) -> String?,
        onSave: (String) -> Unit,
    ) {
        val error = text("", 15f, Ui.BAD).apply { visibility = View.GONE }
        val edit = EditText(this).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setSingleLine(true)
            this.inputType = inputType
            this.hint = hint
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 20f
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            background = cardBackground(Ui.BACKGROUND, Ui.CARD_BORDER, focusStroke = Ui.ACCENT)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setText(initial)
            setSelection(text.length)
        }
        lateinit var dialog: Dialog
        fun submit() {
            val value = edit.text.toString().trim()
            val problem = validate(value)
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                return
            }
            onSave(value)
            dialog.dismiss()
        }
        edit.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done) submit()
            done
        }
        val body = column().apply {
            addView(edit, LinearLayout.LayoutParams(-1, -2))
            addView(error)
        }
        dialog = tvDialog(title, body, "Save" to { submit() }, "Cancel" to { dialog.dismiss() })
        dialog.setOnDismissListener { activeDialog = null }
        edit.requestFocus()
        edit.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------------------------------------------------------- views

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionHeader(value: String) = text(value, 14f, Ui.ACCENT, bold = true).apply {
        letterSpacing = 0.18f
        setPadding(dp(4), dp(26), 0, dp(8))
        isFocusable = false
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

    private fun row(title: String, value: String?, centered: Boolean = false, onClick: () -> Unit): Row {
        val titleView = text(title, 19f, Ui.TEXT, bold = true)
        val valueView = text(value.orEmpty(), 16f, Ui.MUTED).apply {
            visibility = if (value == null) View.GONE else View.VISIBLE
        }
        val view = column().apply {
            background = cardBackground(Ui.CARD, Ui.CARD_BORDER, focusStroke = Ui.ACCENT)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(56)
            gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            addView(titleView)
            addView(valueView)
        }
        if (centered) titleView.gravity = Gravity.CENTER
        return Row(view, titleView, valueView)
    }

    private fun styleSource(row: Row, selected: Boolean) {
        row.view.background = cardBackground(
            Ui.CARD, if (selected) Ui.CARD_SELECTED_BORDER else Ui.CARD_BORDER, focusStroke = Ui.ACCENT,
            borderWidth = if (selected) 3 else 1,
        )
        val base = row.title.text.toString().removePrefix("● ").removePrefix("○ ")
        row.title.text = (if (selected) "● " else "○ ") + base
        row.title.setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
    }

    private fun cardBackground(
        fill: Int,
        stroke: Int,
        focusStroke: Int = stroke,
        radius: Int = 12,
        borderWidth: Int = 1,
    ) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(Ui.CARD_FOCUS.takeIf { fill == Ui.CARD } ?: fill, focusStroke, radius, 3))
        addState(intArrayOf(), shape(fill, stroke, radius, borderWidth))
    }

    private fun shape(fill: Int, stroke: Int, radius: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(width), stroke)
    }

    private companion object {
        val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val persistLock = Mutex()
    }
}
