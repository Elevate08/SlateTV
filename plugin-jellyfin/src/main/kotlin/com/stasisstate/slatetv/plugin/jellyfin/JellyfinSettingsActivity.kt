package com.stasisstate.slatetv.plugin.jellyfin

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

class JellyfinSettingsActivity : JellyfinTvActivity() {
    private lateinit var store: JellyfinConfigStore
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var user: EditText
    private lateinit var tls: Switch
    private lateinit var favorites: Switch
    private lateinit var status: TextView
    private var maxChannels = 12
    private var tap = CardTapAction.OPEN_GUIDE
    private var request: Job? = null
    private var pairing: LanPairingServer? = null
    private var pairingDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = JellyfinConfigStore(applicationContext)
        val config = store.load()
        maxChannels = config.maxHomeChannels
        tap = config.cardTapAction
        val content = rootColumn()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        content.addView(label("Jellyfin Live TV", 30f, true))
        content.addView(button("Pair with phone") { showPairing() })
        url = field(content, "Server URL", config.serverUrl, InputType.TYPE_TEXT_VARIATION_URI, 1500)
        key = field(content, "API key", config.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD, 512)
        user = field(content, "User ID (optional)", config.userId, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 256)
        tls = toggle(content, "Allow self-signed LAN certs", config.allowUntrustedTls)
        content.addView(button("Auto-detect user") {
            perform { snapshot ->
                val detected = JellyfinApiClient(snapshot).detectUserId()
                if (snapshot.serverUrl == url.text.toString().trim() && snapshot.apiKey == key.text.toString().trim()) {
                    user.setText(detected)
                }
                "Jellyfin user detected."
            }
        })
        content.addView(button("Test connection") {
            perform { snapshot ->
                val api = JellyfinApiClient(snapshot)
                val name = api.serverName()
                if (!api.liveTvEnabled()) "$name · Live TV unavailable · 0 channels"
                else {
                    val count = api.channelCount(snapshot.userId.ifBlank { api.detectUserId() })
                    if (count == 0) "$name · No Live TV channels" else "$name · Live TV enabled · $count channels found"
                }
            }
        })
        favorites = toggle(content, "Favorites only", config.favoritesOnly)
        val limitButton = button("Max home channels · $maxChannels") {}
        limitButton.setOnClickListener {
            val limits = listOf(6, 10, 15, 20)
            maxChannels = limits[(limits.indexOf(maxChannels) + 1) % limits.size]
            limitButton.text = "Max home channels · $maxChannels"
        }
        content.addView(limitButton)
        val tapButton = button(tapLabel()) {}
        tapButton.setOnClickListener {
            tap = if (tap == CardTapAction.OPEN_GUIDE) CardTapAction.LAUNCH_JELLYFIN else CardTapAction.OPEN_GUIDE
            tapButton.text = tapLabel()
        }
        content.addView(tapButton)
        content.addView(button("Open Live TV guide") { guide() })
        content.addView(button("Save") {
            perform { snapshot ->
                require(parseServerUrl(snapshot.serverUrl) != null) { "Enter a valid Jellyfin server URL." }
                require(validApiKey(snapshot.apiKey)) { "Enter a valid API key." }
                withContext(Dispatchers.IO) { store.save(snapshot) }
                "Settings saved."
            }
        })
        status = label("")
        status.accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(status)
    }

    private fun field(parent: LinearLayout, title: String, initial: String, input: Int, max: Int): EditText {
        val edit = EditText(this).apply {
            id = android.view.View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = InputType.TYPE_CLASS_TEXT or input
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(max))
            setText(initial)
        }
        parent.addView(label(title).apply { labelFor = edit.id })
        parent.addView(edit)
        return edit
    }

    private fun toggle(parent: LinearLayout, title: String, initial: Boolean) = Switch(this).apply {
        text = title
        isChecked = initial
        minHeight = dp(48)
        isFocusable = true
        parent.addView(this)
    }

    private fun tapLabel() = if (tap == CardTapAction.OPEN_GUIDE) "Card tap · Open full guide"
        else "Card tap · Launch Jellyfin TV app"

    private fun perform(action: suspend (JellyfinConfig) -> String) {
        request?.cancel()
        val snapshot = JellyfinConfig(
            url.text.toString().trim(), key.text.toString().trim(), user.text.toString().trim(),
            maxChannels, favorites.isChecked, tap, tls.isChecked,
        )
        status.text = "Working…"
        request = scope.launch {
            status.text = try {
                action(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                when {
                    parseServerUrl(snapshot.serverUrl) == null -> "Enter a valid Jellyfin server URL."
                    !validApiKey(snapshot.apiKey) -> "Enter a valid API key."
                    else -> "Could not complete the request. Check the server settings and try again."
                }
            }
            Toast.makeText(this@JellyfinSettingsActivity, status.text, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION") // Scan Wi-Fi and Ethernet even when a VPN is the default network.
    private fun showPairing() {
        stopPairing()
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lanCandidates = manager.allNetworks.asSequence().filter { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .mapNotNull { it.address as? Inet4Address }
            .filter { !it.isLoopbackAddress && it.isLanOrTailscale() }
            .toList()
        val address = lanCandidates.firstOrNull { it.isSiteLocalAddress } ?: lanCandidates.firstOrNull()
        if (address == null) {
            status.text = "Connect your TV to a local Wi-Fi or Ethernet network to pair."
            return
        }
        lateinit var activeServer: LanPairingServer
        val server = runCatching {
            LanPairingServer(address) { serverUrl, apiKey, userId ->
                runOnUiThread {
                    if (pairingDialog?.isShowing == true && pairing === activeServer) {
                        url.setText(serverUrl)
                        key.setText(apiKey)
                        user.setText(userId)
                        stopPairing()
                        status.text = "Pairing details received. Select Save to apply."
                    }
                }
            }
        }.getOrNull() ?: run {
            status.text = "Could not start LAN pairing."
            return
        }
        activeServer = server
        pairing = server
        val content = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(label("Scan on the same network · PIN ${server.pin}", 20f, true))
            addView(ImageView(this@JellyfinSettingsActivity).apply {
                setImageBitmap(QrCodeGenerator.generate(server.url))
                contentDescription = "Phone pairing QR code"
            }, LinearLayout.LayoutParams(dp(210), dp(210)))
            addView(label(server.url))
        }
        pairingDialog = AlertDialog.Builder(this).setTitle("Pair with phone").setView(content)
            .setNegativeButton("Close", null).create().apply {
                setOnDismissListener {
                    server.close()
                    if (pairing === server) pairing = null
                }
                show()
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
}
