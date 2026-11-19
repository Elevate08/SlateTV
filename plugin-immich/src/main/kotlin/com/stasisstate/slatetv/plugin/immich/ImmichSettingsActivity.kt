package com.stasisstate.slatetv.plugin.immich

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ImmichSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { SharedPrefsImmichConfigStore(this) }
    private val api = ImmichApiClient()
    private lateinit var draft: ImmichConfig
    private lateinit var status: TextView
    private lateinit var pairButton: Button
    private lateinit var urlButton: Button
    private lateinit var keyButton: Button
    private lateinit var sourceButton: Button
    private lateinit var albumButton: Button
    private lateinit var qualityButton: Button
    private lateinit var orientationButton: Button
    private lateinit var tlsButton: Button
    private var pairDialog: AlertDialog? = null
    private var pairServer: LanConfigServer? = null
    private var autoPair = false
    private var loadingAlbums = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = store.load()
        autoPair = savedInstanceState == null && !draft.isConfigured
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 36))
            setPadding(dp(48), dp(28), dp(48), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            addView(rows)
        }
        setContentView(screen)
        screen.addView(TextView(this).apply {
            text = "Immich Photos"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(14))
        })
        status = TextView(this).apply {
            textSize = 17f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        screen.addView(status)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        pairButton = rows.button("Pair with Phone (QR Code)", ::showPairing)
        urlButton = rows.button("Server URL") {
            edit("Server URL", draft.serverUrl, InputType.TYPE_TEXT_VARIATION_URI) {
                update(draft.copy(serverUrl = it, selectedAlbumIds =
                    if (ImmichConfig.normalizeUrl(it) == draft.normalizedServerUrl) draft.selectedAlbumIds else emptySet()))
            }
        }
        keyButton = rows.button("API Key") {
            edit("API Key", draft.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                update(draft.copy(apiKey = it))
            }
        }
        rows.button("Test Connection", ::testConnection)
        sourceButton = rows.button("Photo Source") {
            val modes = ImmichSourceMode.entries
            update(draft.copy(sourceMode = modes[(draft.sourceMode.ordinal + 1) % modes.size]))
        }
        albumButton = rows.button("Choose Albums", ::chooseAlbums)
        qualityButton = rows.button("Image Quality") {
            val quality = if (draft.imageQuality == ImmichImageQuality.PREVIEW) ImmichImageQuality.FULLSIZE
                else ImmichImageQuality.PREVIEW
            update(draft.copy(imageQuality = quality))
        }
        orientationButton = rows.button("Orientation Filter") {
            update(draft.copy(landscapeOnly = !draft.landscapeOnly))
        }
        tlsButton = rows.button("Allow Self-Signed LAN Certs") {
            update(draft.copy(allowUntrustedTls = !draft.allowUntrustedTls))
        }
        rows.button("Save") { save(draft) }
        refresh()
        pairButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (autoPair) {
            autoPair = false
            showPairing()
        }
    }

    override fun onPause() {
        pairDialog?.dismiss()
        closePairing()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        urlButton.text = "Server URL · ${draft.serverUrl.ifBlank { "Not set" }}"
        keyButton.text = if (draft.apiKey.isBlank()) "API Key · Not set" else "API Key · ••••••••"
        sourceButton.text = "Photo Source · ${draft.sourceMode.displayName}"
        albumButton.text = "Choose Albums · ${draft.selectedAlbumIds.size} selected"
        albumButton.isEnabled = draft.sourceMode == ImmichSourceMode.ALBUMS
        qualityButton.text = if (draft.imageQuality == ImmichImageQuality.PREVIEW) "Image Quality · Preview 1440p"
            else "Image Quality · Full Size"
        orientationButton.text = if (draft.landscapeOnly) "Orientation Filter · Landscape only"
            else "Orientation Filter · All orientations"
        tlsButton.text = "Allow Self-Signed LAN Certs · ${if (draft.allowUntrustedTls) "On" else "Off"}"
    }

    private fun update(config: ImmichConfig) {
        draft = config
        status.visibility = View.GONE
        refresh()
    }

    private fun showStatus(message: String, success: Boolean? = null) {
        status.text = message
        status.setTextColor(Color.WHITE)
        status.setBackgroundColor(when (success) {
            true -> Color.rgb(29, 86, 64)
            false -> Color.rgb(116, 43, 49)
            null -> Color.rgb(42, 62, 88)
        })
        status.visibility = View.VISIBLE
    }

    private fun edit(title: String, value: String, variation: Int, apply: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or variation
            setSingleLine(true)
            setText(value)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> apply(input.text.toString().trim()) }.create()
        input.requestFocus()
        dialog.show()
    }

    private fun testConnection() {
        val config = draft
        showStatus("Testing connection…")
        scope.launch {
            val result = api.testConnection(config)
            if (config != draft) return@launch
            when (result) {
                is ImmichConnectionResult.Success -> showStatus("Connected · ${result.albumCount} albums", true)
                is ImmichConnectionResult.Failure -> showStatus(result.message, false)
            }
        }
    }

    private fun chooseAlbums() {
        if (loadingAlbums) return
        val config = draft
        if (!config.isConfigured) {
            showStatus("Enter the server URL and API key first.", false)
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
                    .setNegativeButton("Cancel") { _, _ -> status.visibility = View.GONE }
                    .setPositiveButton("Apply") { _, _ ->
                        update(draft.copy(selectedAlbumIds = albums.filterIndexed { index, _ -> checked[index] }
                            .map { it.id }.toSet()))
                    }
                    .setOnCancelListener { status.visibility = View.GONE }
                    .show()
                status.visibility = View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (config == draft) showStatus("Could not load albums. Check the connection.", false)
            } finally {
                loadingAlbums = false
            }
        }
    }

    private fun save(config: ImmichConfig) {
        if (api.validateServerUrl(config.serverUrl) == null || config.apiKey.isBlank()) {
            showStatus("Enter a valid server URL and API key.", false)
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ImmichRuntime.mutex.withLock {
                        store.save(config)
                        ImmichRuntime.plugin(store).invalidateQueue()
                    }
                }
                if (config == draft) showStatus("Settings saved.", true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showStatus("Could not save settings.", false)
            }
        }
    }

    private fun showPairing() {
        if (pairServer != null) return
        lateinit var server: LanConfigServer
        server = LanConfigServer(draft.serverUrl, draft.allowUntrustedTls) { url, key, tls ->
            runOnUiThread {
                if (pairServer !== server) return@runOnUiThread
                pairDialog?.dismiss()
                closePairing()
                val albums = if (ImmichConfig.normalizeUrl(url) == draft.normalizedServerUrl) draft.selectedAlbumIds
                    else emptySet()
                update(draft.copy(serverUrl = url, apiKey = key, allowUntrustedTls = tls, selectedAlbumIds = albums))
                showStatus("Credentials received. Select Save to apply them.", true)
            }
        }
        pairServer = server
        val url = try {
            server.start()
        } catch (_: Exception) {
            closePairing()
            showStatus("Could not start phone pairing.", false)
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            QrCodeGenerator.generate(url)?.let { code ->
                addView(ImageView(context).apply {
                    setImageBitmap(code)
                    contentDescription = "Phone pairing QR code"
                }, dp(208), dp(208))
            }
            addView(TextView(context).apply {
                text = "Open ${url.substringBefore('?')} on your phone\nPIN  ${server.pin}"
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }
        pairDialog = AlertDialog.Builder(this).setTitle("Pair with Phone")
            .setView(content).setNegativeButton("Close", null).create().apply {
                setOnDismissListener { if (pairServer === server) closePairing() }
                show()
            }
    }

    private fun closePairing() {
        pairDialog = null
        pairServer?.close()
        pairServer = null
    }

    private fun LinearLayout.button(label: String, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        textSize = 18f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(22), 0, dp(22), 0)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.rgb(33, 40, 51), Color.rgb(54, 94, 148), Color.rgb(36, 51, 72)),
        )
        setOnClickListener { action() }
        addView(this, LinearLayout.LayoutParams(-1, dp(60)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
