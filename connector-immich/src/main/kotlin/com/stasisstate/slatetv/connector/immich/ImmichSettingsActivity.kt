package com.stasisstate.slatetv.connector.immich

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.SecureRandom
import java.util.Date

/**
 * 10-foot Android TV settings for the built-in Immich connector. Launched explicitly from Slate TV Settings;
 * it is not exported and has no launcher intent-filter.
 *
 * Two ways to connect, both using only TV → Immich traffic (nothing ever connects to the TV):
 * - Sign in with email + password on the TV, which mints a scoped API key (full library access).
 * - Pair with phone: the TV shows a QR code and a short code; the user shares an album from Immich on their phone
 *   with that code as the link's custom URL, and the TV finds it by polling Immich (read-only, one album).
 *
 * Self-signed HTTPS servers are handled with Trust-On-First-Use pinning: when system trust rejects the server,
 * its leaf certificate is probed and shown (host, subject, issuer, validity, SHA-256) for explicit confirmation.
 */
class ImmichSettingsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { SharedPrefsImmichConfigStore(applicationContext) }
    private val api get() = ImmichConnectorRuntime.api
    private val discovery by lazy { ImmichServerDiscovery(this) }
    private lateinit var draft: ImmichConfig
    private lateinit var savedConfig: ImmichConfig

    private lateinit var statusPill: TextView
    private lateinit var discoveredHeader: TextView
    private lateinit var discoveredRows: LinearLayout
    private lateinit var signInRow: Row
    private lateinit var urlRow: Row
    private lateinit var keyRow: Row
    private lateinit var sourceRow: Row
    private lateinit var albumRow: Row
    private lateinit var qualityRow: Row
    private lateinit var orientationRow: Row
    private lateinit var certRow: Row

    private var statusOverride: Pair<String, Kind>? = null
    private var pairDialog: AlertDialog? = null
    private var pairJob: Job? = null
    private var signInJob: Job? = null
    private var preflightJob: Job? = null
    private var probeJob: Job? = null
    private var certDialog: AlertDialog? = null
    private var loadingAlbums = false

    private enum class Kind(val fill: Int, val text: Int) {
        NEUTRAL(0xFF1E293B.toInt(), 0xFFCBD5E1.toInt()),
        WORKING(0xFF0C3A52.toInt(), 0xFF7DD3FC.toInt()),
        SUCCESS(0xFF0F3D2E.toInt(), 0xFF86EFAC.toInt()),
        ERROR(0xFF4A1D24.toInt(), 0xFFFCA5A5.toInt()),
        WARNING(0xFF4A3510.toInt(), 0xFFFCD34D.toInt()),
    }

    private class Row(val view: LinearLayout, val title: TextView, val value: TextView) {
        fun set(valueText: String) {
            value.text = valueText
        }

        fun setActive(active: Boolean) {
            view.isEnabled = active
            view.isFocusable = active
            view.alpha = if (active) 1f else 0.4f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = store.load()
        savedConfig = draft

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12), dp(24))
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(64), dp(36), dp(64), dp(24))
        }
        setContentView(screen)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        header.addView(TextView(this).apply {
            text = "Immich Photos"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(statusPill, LinearLayout.LayoutParams(-2, -2))
        screen.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            isVerticalScrollBarEnabled = false
            addView(rows)
        }
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ── CONNECTION ──────────────────────────────────────────────────────────────
        rows.section("CONNECTION")
        signInRow = rows.row(
            "Sign in with email & password",
            "Full library access — Slate TV creates its own read-only key; the password is never stored",
            ::startSignIn,
        )
        rows.row(
            "Pair with phone (QR)",
            "No password on the TV — share an album from Immich on your phone. Works through your Immich server only.",
            ::startPhonePairing,
        )
        discoveredHeader = rows.section("FOUND ON YOUR NETWORK").also { it.visibility = View.GONE }
        discoveredRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        rows.addView(discoveredRows, LinearLayout.LayoutParams(-1, -2))
        urlRow = rows.row("Server address", "") {
            editServerUrl { }
        }
        rows.row("Test connection", "Check the server address and sign-in", ::testConnection)

        // ── PHOTO LIBRARY ───────────────────────────────────────────────────────────
        rows.section("PHOTO LIBRARY")
        sourceRow = rows.row("Photo source", "") {
            val modes = ImmichSourceMode.entries
            update(draft.copy(sourceMode = modes[(draft.sourceMode.ordinal + 1) % modes.size]))
        }
        albumRow = rows.row("Choose albums", "", ::chooseAlbums)
        qualityRow = rows.row("Image quality", "") {
            val quality = if (draft.imageQuality == ImmichImageQuality.PREVIEW) ImmichImageQuality.FULLSIZE
            else ImmichImageQuality.PREVIEW
            update(draft.copy(imageQuality = quality))
        }
        orientationRow = rows.row("Orientation filter", "") {
            update(draft.copy(landscapeOnly = !draft.landscapeOnly))
        }

        // ── ADVANCED ────────────────────────────────────────────────────────────────
        rows.section("ADVANCED")
        keyRow = rows.row("API key (manual)", "") {
            edit(
                title = "API Key",
                value = draft.apiKey,
                hint = "Paste or type your Immich API key",
                inputType = API_KEY_INPUT_TYPE,
                ime = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
            ) { if (it.isNotBlank() || !draft.usesSharedLink) update(draft.withApiKey(it)) }
        }
        certRow = rows.row("Pinned certificate", "", ::inspectCertificate)

        rows.addView(TextView(this).apply { height = dp(8) })
        rows.primaryButton("Save") { save(draft) }

        refresh()
        signInRow.view.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        discovery.start { servers -> showDiscovered(servers) }
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    override fun onPause() {
        stopPairing()
        super.onPause()
    }

    override fun onDestroy() {
        certDialog?.dismiss()
        certDialog = null
        scope.cancel()
        super.onDestroy()
    }

    // ── State & rendering ───────────────────────────────────────────────────────────

    private fun refresh() {
        urlRow.set(draft.normalizedServerUrl.ifBlank { "Not set — e.g. https://photos.example.com" })
        keyRow.set(
            when {
                draft.usesSharedLink -> "Not used — paired with a shared album"
                draft.apiKey.isBlank() -> "Not set"
                else -> "Saved  ••••••••"
            },
        )
        if (draft.usesSharedLink) {
            sourceRow.set("Shared album: ${draft.sharedAlbumName.ifBlank { "paired from phone" }}")
            albumRow.set("Sign in with email & password to choose other albums")
        } else {
            sourceRow.set(draft.sourceMode.displayName)
            albumRow.set("${draft.selectedAlbumIds.size} selected")
        }
        sourceRow.setActive(!draft.usesSharedLink)
        albumRow.setActive(!draft.usesSharedLink && draft.sourceMode == ImmichSourceMode.ALBUMS)
        qualityRow.set(
            if (draft.imageQuality == ImmichImageQuality.PREVIEW) "Preview 1440p (recommended)" else "Full size",
        )
        orientationRow.set(if (draft.landscapeOnly) "Landscape only" else "All orientations")
        val pin = draft.pinnedCert
        certRow.set(
            when {
                pin == null && draft.normalizedServerUrl.startsWith("https://", ignoreCase = true) ->
                    "None — system trust (select to inspect the server certificate)"
                pin == null -> "None — not used for http:// servers"
                draft.activePinnedCertDer == null -> "Pinned for another server — select to inspect or clear"
                else -> "SHA-256 ${pin.formattedFingerprint?.take(23) ?: "invalid"}…  (Inspect / Clear)"
            },
        )
        renderStatus()
    }

    private fun update(config: ImmichConfig) {
        draft = config
        statusOverride = null
        refresh()
    }

    private fun showStatus(message: String, success: Boolean? = null) {
        showStatus(message, when (success) {
            true -> Kind.SUCCESS
            false -> Kind.ERROR
            null -> Kind.WORKING
        })
    }

    private fun showStatus(message: String, kind: Kind) {
        statusOverride = message to kind
        renderStatus()
    }

    private fun renderStatus() {
        val (message, kind) = statusOverride ?: when {
            !draft.isConfigured -> "Setup needed — sign in or pair with your phone" to Kind.WARNING
            draft != savedConfig -> "Unsaved changes — select Save" to Kind.WARNING
            draft.usesSharedLink -> "Saved — showing your shared album" to Kind.NEUTRAL
            else -> "Saved — ready for Slate TV" to Kind.NEUTRAL
        }
        statusPill.text = "●  $message"
        statusPill.setTextColor(kind.text)
        statusPill.background = GradientDrawable().apply {
            setColor(kind.fill)
            cornerRadius = dp(40).toFloat()
        }
    }

    private fun showDiscovered(servers: List<DiscoveredImmichServer>) {
        discoveredRows.removeAllViews()
        servers.forEach { server ->
            discoveredRows.row(server.name, server.url) {
                update(draft.withServerUrl(server.url))
                startSignIn()
            }
        }
        val visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
        discoveredHeader.visibility = visibility
        discoveredRows.visibility = visibility
    }

    // ── Input dialogs ───────────────────────────────────────────────────────────────

    private fun field(
        hintText: String,
        value: String,
        type: Int,
        ime: Int,
    ) = EditText(this).apply {
        inputType = type
        imeOptions = ime
        setSingleLine(true)
        hint = hintText
        setHintTextColor(TEXT_MUTED)
        setTextColor(TEXT_PRIMARY)
        textSize = 20f
        setText(value)
        setSelection(text.length)
    }

    private fun showKeyboardWith(dialog: AlertDialog) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun edit(
        title: String,
        value: String,
        hint: String,
        inputType: Int,
        ime: Int,
        apply: (String) -> Unit,
    ) {
        val input = field(hint, value, inputType, ime)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input.padded())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> apply(input.text.toString().trim()) }.create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                apply(input.text.toString().trim())
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        showKeyboardWith(dialog)
        styleDialog(dialog)
        input.requestFocus()
        dialog.show()
    }

    private fun editServerUrl(then: () -> Unit) {
        edit(
            title = "Server address — e.g. 192.168.1.20 or photos.example.com",
            value = draft.serverUrl,
            hint = "192.168.1.20 or photos.example.com (port 2283 is tried automatically)",
            inputType = SERVER_URL_INPUT_TYPE,
            ime = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
        ) {
            update(draft.withServerUrl(it))
            if (it.isNotBlank() && api.validateServerUrl(it) == null) {
                showStatus("That doesn't look like a valid server address.", false)
            } else {
                then()
            }
        }
    }

    private fun startSignIn() {
        withResolvedServer { promptCredentials() }
    }

    /**
     * Makes sure a server address is set, finds the address Immich actually answers on (adding `:2283` / the right
     * scheme for bare input), and reviews + pins an untrusted certificate — all BEFORE [then] runs. Sign-in uses this
     * so the password is never typed or held in memory across the trust prompt.
     */
    private fun withResolvedServer(then: () -> Unit) {
        if (api.validateServerUrl(draft.serverUrl) == null) {
            editServerUrl { if (api.validateServerUrl(draft.serverUrl) != null) withResolvedServer(then) }
            return
        }
        if (preflightJob?.isActive == true || signInJob?.isActive == true) return
        val typed = draft.serverUrl
        val pin = draft.pinnedCert
        showStatus("Looking for Immich…")
        preflightJob = scope.launch {
            val probe = api.findServer(typed, pin)
            if (typed != draft.serverUrl) return@launch
            when (probe) {
                is ImmichServerProbe.Found -> {
                    if (ImmichConfig.normalizeUrl(probe.serverUrl) != draft.normalizedServerUrl) {
                        update(draft.withServerUrl(probe.serverUrl))
                    }
                    statusOverride = null
                    renderStatus()
                    then()
                }
                is ImmichServerProbe.UntrustedCertificate -> {
                    if (ImmichConfig.normalizeUrl(probe.serverUrl) != draft.normalizedServerUrl) {
                        update(draft.withServerUrl(probe.serverUrl))
                    }
                    reviewServerCertificate(probe.serverUrl) { withResolvedServer(then) }
                }
                is ImmichServerProbe.NotFound -> showStatus(probe.message, false)
            }
        }
    }

    private fun promptCredentials() {
        if (signInJob?.isActive == true) return
        val rememberedEmail = store.loadLastEmail()
        val email = field(
            "Email", rememberedEmail, EMAIL_INPUT_TYPE,
            EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
        )
        val password = field(
            "Password", "", PASSWORD_INPUT_TYPE,
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(context).apply {
                text = "Signing in to ${draft.normalizedServerUrl}\nYour password is used once and never stored."
                textSize = 15f
                setTextColor(TEXT_MUTED)
                setPadding(0, 0, 0, dp(8))
            })
            addView(email)
            addView(password)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Sign in to Immich").setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign In", null).create()
        val submit = {
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()
            if (emailText.isEmpty()) {
                email.error = "Enter your email"
                email.requestFocus()
            } else if (passwordText.isEmpty()) {
                password.error = "Enter your password"
                password.requestFocus()
            } else {
                password.text.clear()
                dialog.dismiss()
                signIn(emailText, passwordText)
            }
        }
        email.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                password.requestFocus()
                true
            } else {
                false
            }
        }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        showKeyboardWith(dialog)
        styleDialog(dialog)
        // Returning users only need to type the password.
        if (rememberedEmail.isBlank()) email.requestFocus() else password.requestFocus()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────

    private fun signIn(email: String, password: String) {
        val serverUrl = draft.serverUrl
        val pin = draft.pinnedCert
        showStatus("Signing in…")
        signInJob = scope.launch {
            when (val result = api.signInAndCreateApiKey(serverUrl, email, password, pin)) {
                is ImmichSignInResult.Failure -> if (result.isUntrustedCertificate) {
                    // The certificate changed between preflight and sign-in: review it, then ask to sign in again.
                    showStatus("Server certificate changed — review it, then sign in again.", Kind.WARNING)
                    reviewServerCertificate(serverUrl) { startSignIn() }
                } else {
                    showStatus(result.message, false)
                }
                is ImmichSignInResult.Success -> {
                    runCatching { store.saveLastEmail(email) }
                    val config = draft.withServerUrl(result.serverUrl).withApiKey(result.apiKey)
                    draft = config
                    refresh()
                    showStatus("Signed in — saving key…")
                    if (!persist(config)) return@launch
                    showStatus("Signed in and saved — testing connection…")
                    when (val test = api.testConnection(config)) {
                        is ImmichConnectionResult.Success ->
                            showStatus("Signed in and saved · ${test.albumCount} albums found", true)
                        is ImmichConnectionResult.Failure ->
                            showStatus("Saved, but the connection test failed: ${test.message}", false)
                    }
                }
            }
        }
    }

    private fun testConnection() {
        val config = draft
        showStatus("Testing connection…")
        scope.launch {
            val result = api.testConnection(config)
            if (config != draft) return@launch
            when {
                result is ImmichConnectionResult.Success -> showStatus("Connected · ${result.albumCount} albums", true)
                result is ImmichConnectionResult.Failure && result.isUntrustedCertificate ->
                    reviewServerCertificate(config.serverUrl) { testConnection() }
                result is ImmichConnectionResult.Failure -> showStatus(result.message, false)
            }
        }
    }

    private fun chooseAlbums() {
        if (loadingAlbums) return
        val config = draft
        if (config.usesSharedLink) {
            showStatus("Paired with one shared album. Sign in with email & password to choose albums.", Kind.NEUTRAL)
            return
        }
        if (!config.isConfigured) {
            showStatus("Sign in or enter the server address and API key first.", false)
            return
        }
        loadingAlbums = true
        showStatus("Loading albums…")
        scope.launch {
            try {
                val albums = api.listAlbums(config)
                if (config != draft || !hasWindowFocus()) return@launch
                if (albums.isEmpty()) {
                    showStatus("No albums found.", false)
                    return@launch
                }
                val checked = BooleanArray(albums.size) { albums[it].id in config.selectedAlbumIds }
                AlertDialog.Builder(this@ImmichSettingsActivity)
                    .setTitle("Choose Albums")
                    .setMultiChoiceItems(albums.map { "${it.name} · ${it.assetCount} items" }.toTypedArray(), checked) {
                        _, index, selected -> checked[index] = selected
                    }
                    .setNegativeButton("Cancel") { _, _ -> statusOverride = null; renderStatus() }
                    .setPositiveButton("Apply") { _, _ ->
                        update(draft.copy(selectedAlbumIds = albums.filterIndexed { index, _ -> checked[index] }
                            .map { it.id }.toSet()))
                    }
                    .setOnCancelListener { statusOverride = null; renderStatus() }
                    .create()
                    .also(::styleDialog)
                    .show()
                statusOverride = null
                renderStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (config == draft) showStatus("Could not load albums. Check the connection.", false)
            } finally {
                loadingAlbums = false
            }
        }
    }

    /** Writes [config] (API key encrypted by the store) and invalidates cached plugin state. */
    private suspend fun persist(config: ImmichConfig): Boolean = try {
        withContext(Dispatchers.IO) {
            store.save(config)
            ImmichConnectorRuntime.invalidateAll()
        }
        savedConfig = config
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        showStatus("Could not save settings.", false)
        false
    }

    private fun save(config: ImmichConfig) {
        if (api.validateServerUrl(config.serverUrl) == null || !config.isConfigured) {
            showStatus("Sign in, pair with your phone, or enter a server address and API key.", false)
            return
        }
        scope.launch {
            if (persist(config)) {
                if (config == draft) showStatus("Settings saved.", true) else renderStatus()
            }
        }
    }

    // ── Phone pairing (through the Immich server only) ─────────────────────────────

    /**
     * QR pairing without any connection to the TV: the TV shows a code, the user creates an album shared link
     * with that code as its custom URL in Immich on their phone, and the TV polls `GET /api/shared-links/me`.
     */
    private fun startPhonePairing() {
        if (pairJob?.isActive == true) return
        withResolvedServer {
            val serverUrl = draft.normalizedServerUrl
            val pin = draft.pinnedCert
            showStatus("Checking Immich version…")
            pairJob = scope.launch {
                val version = api.serverVersion(serverUrl, pin)
                if (serverUrl != draft.normalizedServerUrl) return@launch
                if (version != null && version < ImmichServerVersion.SHARED_LINK_SLUG_MIN) {
                    showStatus(
                        "Phone pairing needs Immich v1.137 or newer (this server is $version). " +
                            "Use Sign in with email & password instead.",
                        false,
                    )
                    return@launch
                }
                statusOverride = null
                renderStatus()
                runPairing(serverUrl, pin, newPairingCode())
            }
        }
    }

    private suspend fun runPairing(serverUrl: String, pin: PinnedCert?, code: String) {
        val progress = TextView(this).apply {
            text = "Waiting for the shared album…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ACCENT)
            setPadding(0, dp(12), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(12), dp(28), dp(12))
        }
        QrCodeGenerator.generate("$serverUrl/albums")?.let { qr ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context).apply {
                    setImageBitmap(qr)
                    contentDescription = "QR code that opens your Immich albums on your phone"
                }, dp(200), dp(200))
                addView(TextView(context).apply {
                    text = "Scan to open Immich\nin your phone's browser"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(TEXT_MUTED)
                    setPadding(0, dp(6), 0, 0)
                })
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(28) })
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "On your phone, in Immich:\n" +
                    "1.  Open the album to show on the TV\n" +
                    "2.  Share  →  Create link\n" +
                    "3.  Set Custom URL to the code below (no password)\n" +
                    "4.  Create the link"
                textSize = 17f
                setTextColor(TEXT_PRIMARY)
                setLineSpacing(0f, 1.2f)
            })
            addView(TextView(context).apply {
                text = code
                textSize = 34f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.08f
                setTextColor(ACCENT)
                setPadding(0, dp(14), 0, dp(4))
                contentDescription = "Pairing code ${code.toCharArray().joinToString(" ")}"
            })
            addView(TextView(context).apply {
                text = "Slate TV only talks to $serverUrl — nothing connects to the TV."
                textSize = 13f
                setTextColor(TEXT_MUTED)
            })
            addView(progress)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val dialog = AlertDialog.Builder(this).setTitle("Pair with Phone")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .create()
        val job = pairJob
        dialog.setOnDismissListener {
            if (pairDialog === dialog) pairDialog = null
            if (pairJob === job) {
                pairJob?.cancel()
                pairJob = null
            }
        }
        pairDialog = dialog
        styleDialog(dialog)
        // A screensaver would pause the activity and cancel pairing while the user is busy on their phone.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show()

        val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
        var failures = 0
        while (System.currentTimeMillis() < deadline) {
            if (serverUrl != draft.normalizedServerUrl) break
            val result = api.findSharedLinkBySlug(serverUrl, code, pin)
            when (result) {
                is ImmichSharedLinkLookup.Found -> {
                    finishPairing(dialog, result)
                    return
                }
                ImmichSharedLinkLookup.NotYet -> progress.text = "Waiting for the shared album…"
                ImmichSharedLinkLookup.PasswordProtected ->
                    progress.text = "That link has a password. Edit the link in Immich and remove it — still waiting…"
                ImmichSharedLinkLookup.NotAnAlbum ->
                    progress.text = "That link shares single photos. Share an album instead — still waiting…"
                is ImmichSharedLinkLookup.Failure -> if (result.isUntrustedCertificate) {
                    pairDialog = null
                    dialog.dismiss()
                    reviewServerCertificate(serverUrl) { startPhonePairing() }
                    return
                } else {
                    failures++
                    progress.text = "${result.message} Retrying…"
                }
            }
            if (result !is ImmichSharedLinkLookup.Failure) failures = 0
            // Back off on errors so a strict proxy (fail2ban etc.) isn't hammered.
            delay(PAIRING_POLL_MS * (1L shl failures.coerceAtMost(3)))
        }
        progress.text = "Pairing timed out. Close this and try again."
        progress.setTextColor(ERROR_TEXT)
    }

    private suspend fun finishPairing(dialog: AlertDialog, link: ImmichSharedLinkLookup.Found) {
        pairJob = null // keep this coroutine alive through the dismiss listener
        dialog.dismiss()
        val config = draft.withSharedAlbum(link.key, link.albumId, link.albumName)
        draft = config
        refresh()
        showStatus("Paired — saving…")
        if (!persist(config)) return
        showStatus("Testing connection…")
        when (val test = api.testConnection(config)) {
            is ImmichConnectionResult.Success -> showStatus(
                "Paired with “${link.albumName}” (${link.assetCount} items). " +
                    "You can remove the custom URL in Immich now; the link keeps working.",
                true,
            )
            is ImmichConnectionResult.Failure ->
                showStatus("Paired and saved, but the connection test failed: ${test.message}", false)
        }
    }

    private fun stopPairing() {
        pairJob?.cancel()
        pairJob = null
        pairDialog?.dismiss()
        pairDialog = null
    }

    /** `slate-` + 8 characters from an unambiguous lowercase alphabet (~40 bits; easy to type on a phone). */
    private fun newPairingCode(): String {
        val random = SecureRandom()
        return "slate-" + (1..8).map { PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)] }.joinToString("")
    }

    // ── TOFU certificate pinning ────────────────────────────────────────────────────

    /** Probes [serverUrl]'s leaf certificate and asks the user to trust & pin it; runs [onPinned] after pinning. */
    private fun reviewServerCertificate(serverUrl: String, inspectOnly: Boolean = false, onPinned: () -> Unit) {
        if (probeJob?.isActive == true || certDialog?.isShowing == true) return
        showStatus("Checking the server certificate…")
        probeJob = scope.launch {
            val probed = try {
                withContext(Dispatchers.IO) { TofuTls.probeLeafCertificate(ImmichConfig.normalizeUrl(serverUrl)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (ImmichConfig.normalizeUrl(serverUrl) != draft.normalizedServerUrl) return@launch
            if (probed == null) {
                showStatus("Could not read the server's certificate.", false)
                return@launch
            }
            showCertificateDialog(probed, inspectOnly, onPinned)
        }
    }

    private fun showCertificateDialog(probed: TofuTls.ProbedCertificate, inspectOnly: Boolean, onPinned: () -> Unit) {
        val previous = draft.pinnedCert?.takeIf { it.origin == "${probed.host.lowercase()}:${probed.port}" }
        val changed = previous != null && previous.sha256Hex != probed.sha256Hex
        val now = Date()
        val content = certificateDetails(
            host = "${probed.host}:${probed.port}",
            subject = probed.subjectCn,
            issuer = probed.issuerCn + if (probed.isSelfSigned) "  (self-signed)" else "",
            validFrom = probed.notBefore,
            validUntil = probed.notAfter,
            fingerprint = probed.formattedFingerprint,
            warning = when {
                changed -> "WARNING: this certificate is DIFFERENT from the one you pinned " +
                    "(${previous?.formattedFingerprint?.take(23)}…). Only continue if you replaced the server's certificate."
                now.after(probed.notAfter) -> "This certificate has expired."
                now.before(probed.notBefore) -> "This certificate is not yet valid."
                inspectOnly -> "Pinning is only needed for self-signed or private-CA certificates. A pinned " +
                    "certificate must be confirmed again whenever the server renews it."
                else -> "This server's certificate isn't trusted by the system. Compare the fingerprint with your " +
                    "server before trusting it. Slate TV will then accept only this exact certificate."
            },
            warningColor = when {
                changed -> ERROR_TEXT
                inspectOnly -> TEXT_MUTED
                else -> WARNING_TEXT
            },
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                when {
                    changed -> "Certificate Changed"
                    inspectOnly -> "Server Certificate"
                    else -> "Trust This Server?"
                },
            )
            .setView(content)
            .setNegativeButton("Cancel") { _, _ -> showStatus("Certificate not trusted.", Kind.WARNING) }
            .setPositiveButton("Trust & Pin This Certificate") { _, _ ->
                update(draft.copy(pinnedCert = PinnedCert.from(probed)))
                showStatus("Certificate pinned — select Save to keep it.", true)
                onPinned()
            }
            .setOnCancelListener { showStatus("Certificate not trusted.", Kind.WARNING) }
            .create()
        dialog.setOnDismissListener { if (certDialog === dialog) certDialog = null }
        certDialog = dialog
        styleDialog(dialog)
        dialog.show()
        // Safer default: D-pad focus starts on Cancel.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    /** ADVANCED row: inspect / clear the pinned certificate, or inspect the live server certificate when none. */
    private fun inspectCertificate() {
        val pin = draft.pinnedCert
        if (pin == null) {
            if (draft.normalizedServerUrl.startsWith("https://", ignoreCase = true)) {
                reviewServerCertificate(draft.serverUrl, inspectOnly = true) { }
            } else {
                showStatus("Certificate pinning is only used for https:// servers.", Kind.NEUTRAL)
            }
            return
        }
        val cert = pin.der?.let { runCatching { TofuTls.parseCertificate(it) }.getOrNull() }
        val content = certificateDetails(
            host = pin.origin,
            subject = cert?.let { TofuTls.commonName(it.subjectX500Principal) } ?: "(unreadable)",
            issuer = cert?.let { TofuTls.commonName(it.issuerX500Principal) } ?: "(unreadable)",
            validFrom = cert?.notBefore,
            validUntil = cert?.notAfter,
            fingerprint = pin.formattedFingerprint ?: "(invalid)",
            warning = if (draft.activePinnedCertDer == null) {
                "This pin belongs to a different server address and is not in use."
            } else {
                "Only this exact certificate is accepted for this server."
            },
            warningColor = TEXT_MUTED,
        )
        AlertDialog.Builder(this)
            .setTitle("Pinned Certificate")
            .setView(content)
            .setNegativeButton("Close", null)
            .setPositiveButton("Clear Pinned Certificate") { _, _ ->
                update(draft.copy(pinnedCert = null))
                showStatus("Pinned certificate cleared — select Save to apply.", Kind.WARNING)
            }
            .create()
            .also(::styleDialog)
            .show()
    }

    private fun certificateDetails(
        host: String,
        subject: String,
        issuer: String,
        validFrom: Date?,
        validUntil: Date?,
        fingerprint: String,
        warning: String,
        warningColor: Int,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(8))
        }
        box.addView(TextView(this).apply {
            text = warning
            textSize = 16f
            setTextColor(warningColor)
            setPadding(0, 0, 0, dp(14))
        })
        fun detail(label: String, value: String, mono: Boolean = false) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                setTextColor(TEXT_MUTED)
                setPadding(0, dp(8), 0, dp(2))
            })
            box.addView(TextView(this).apply {
                text = value
                textSize = if (mono) 16f else 18f
                if (mono) typeface = Typeface.MONOSPACE
                setTextColor(TEXT_PRIMARY)
                setTextIsSelectable(false)
            })
        }
        detail("HOST", host)
        detail("SUBJECT", subject)
        detail("ISSUER", issuer)
        detail(
            "VALID",
            "${validFrom?.let(TofuTls::formatDate) ?: "?"}  →  ${validUntil?.let(TofuTls::formatDate) ?: "?"}",
        )
        // 32 bytes → two lines of 16 for TV readability.
        val bytes = fingerprint.split(':')
        val formatted = if (bytes.size == 32) {
            bytes.take(16).joinToString(":") + "\n" + bytes.drop(16).joinToString(":")
        } else {
            fingerprint
        }
        detail("SHA-256 FINGERPRINT", formatted, mono = true)
        return ScrollView(this).apply { addView(box) }
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), FOCUSED_FILL)
        })
    }

    // ── View builders ───────────────────────────────────────────────────────────────

    private fun View.padded(): View = LinearLayout(this@ImmichSettingsActivity).apply {
        setPadding(dp(24), dp(8), dp(24), dp(8))
        addView(this@padded, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.section(label: String) = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.14f
        setTextColor(TEXT_MUTED)
        setPadding(dp(4), dp(22), 0, dp(10))
        addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun card(fill: Int, focusStroke: Int): StateListDrawable {
        fun shape(color: Int, stroke: Int?) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
            stroke?.let { setStroke(dp(3), it) }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(FOCUSED_FILL, focusStroke))
            addState(intArrayOf(android.R.attr.state_pressed), shape(FOCUSED_FILL, focusStroke))
            addState(intArrayOf(), shape(fill, null))
        }
    }

    private fun LinearLayout.row(title: String, value: String, action: () -> Unit): Row {
        val titleView = TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }
        val valueView = TextView(context).apply {
            text = value
            textSize = 15f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
            visibility = View.VISIBLE
        }
        val view = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = card(CARD, FOCUS_RING)
            setPadding(dp(26), dp(14), dp(26), dp(14))
            minimumHeight = dp(76)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.015f else 1f).scaleY(if (focused) 1.015f else 1f)
                    .setDuration(120).start()
            }
            addView(titleView)
            addView(valueView)
        }
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return Row(view, titleView, valueView)
    }

    private fun LinearLayout.primaryButton(label: String, action: () -> Unit) = TextView(context).apply {
        id = View.generateViewId()
        text = label
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(BG)
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            fun shape(stroke: Int?) = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(14).toFloat()
                stroke?.let { setStroke(dp(4), it) }
            }
            addState(intArrayOf(android.R.attr.state_focused), shape(TEXT_PRIMARY))
            addState(intArrayOf(android.R.attr.state_pressed), shape(TEXT_PRIMARY))
            addState(intArrayOf(), shape(null))
        }
        setOnClickListener { action() }
        setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.02f else 1f).scaleY(if (focused) 1.02f else 1f)
                .setDuration(120).start()
        }
        addView(this, LinearLayout.LayoutParams(-1, dp(68)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PAIRING_POLL_MS = 5_000L
        private const val PAIRING_TIMEOUT_MS = 15 * 60_000L
        private const val PAIRING_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        internal const val SERVER_URL_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        internal const val EMAIL_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        internal const val PASSWORD_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        internal const val API_KEY_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        private val BG = Color.parseColor("#0A0D12")
        private val CARD = Color.parseColor("#141923")
        private val FOCUSED_FILL = Color.parseColor("#1B2332")
        private val FOCUS_RING = Color.parseColor("#F8FAFC")
        private val ACCENT = Color.parseColor("#38BDF8")
        private val TEXT_PRIMARY = Color.parseColor("#F8FAFC")
        private val TEXT_MUTED = Color.parseColor("#94A3B8")
        private val WARNING_TEXT = Color.parseColor("#FCD34D")
        private val ERROR_TEXT = Color.parseColor("#FCA5A5")
    }
}
