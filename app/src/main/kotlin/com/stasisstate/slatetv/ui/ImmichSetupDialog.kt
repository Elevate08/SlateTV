package com.stasisstate.slatetv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.plugin.immich.ImmichAlbum
import com.stasisstate.slatetv.plugin.immich.ImmichApiClient
import com.stasisstate.slatetv.plugin.immich.ImmichConfig
import com.stasisstate.slatetv.plugin.immich.ImmichConfigStore
import com.stasisstate.slatetv.plugin.immich.ImmichConnectionResult
import com.stasisstate.slatetv.plugin.immich.ImmichImageQuality
import com.stasisstate.slatetv.plugin.immich.ImmichSourceMode
import com.stasisstate.slatetv.plugin.immich.LanConfigServer
import com.stasisstate.slatetv.plugin.immich.QrCodeGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV-friendly Immich plugin setup modal supporting:
 * 1. Instant Phone Pairing via local LAN QR code + 6-digit PIN (zero remote typing!).
 * 2. Direct TV editing of Server URL & API key.
 * 3. Live connection test & diagnostic feedback.
 * 4. Source mode selection + multi-select Immich Album picker.
 * 5. Landscape filter & image quality toggles.
 */
class ImmichSetupDialog(
    private val context: Context,
    private val configStore: ImmichConfigStore,
    private val apiClient: ImmichApiClient,
    private val onConfigSaved: (ImmichConfig) -> Unit,
) {
    private var dialog: Dialog? = null
    private var lanServer: LanConfigServer? = null
    private var uiScope: CoroutineScope? = null
    private var workingConfig: ImmichConfig = configStore.load()

    private lateinit var urlValueView: TextView
    private lateinit var keyValueView: TextView
    private lateinit var statusBannerView: TextView
    private lateinit var sourceModeValueView: TextView
    private lateinit var albumsValueView: TextView
    private lateinit var qualityValueView: TextView
    private lateinit var landscapeValueView: TextView
    private lateinit var untrustedTlsValueView: TextView
    private lateinit var qrPanel: LinearLayout
    private lateinit var qrImageView: ImageView
    private lateinit var qrUrlText: TextView

    fun show() {
        workingConfig = configStore.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope

        val dlg = Dialog(context, R.style.Theme_SlateTV_Dialog)
        dialog = dlg

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_surface)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        // Left column: Configuration rows
        val leftScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(470), dp(460))
            isScrollbarFadingEnabled = false
        }
        val leftCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(16), 0)
        }
        leftScroll.addView(leftCol)

        leftCol.addView(TextView(context).apply {
            text = "Immich Plugin Setup"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(4))
        })
        leftCol.addView(TextView(context).apply {
            text = "Authenticate via x-api-key header. Scan the QR code with your phone to paste credentials in seconds."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(12))
        })

        statusBannerView = TextView(context).apply {
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        leftCol.addView(statusBannerView)

        val (urlRow, urlVal) = createActionRow("Server URL", "") { promptTextInput("Immich Server URL", workingConfig.serverUrl, isSecret = false) {
            workingConfig = workingConfig.copy(serverUrl = ImmichConfig.normalizeUrl(it))
            refreshLabels()
        } }
        urlValueView = urlVal
        leftCol.addView(urlRow)

        val (keyRow, keyVal) = createActionRow("API Key", "") { promptTextInput("Immich API Key", workingConfig.apiKey, isSecret = true) {
            workingConfig = workingConfig.copy(apiKey = it.trim())
            refreshLabels()
        } }
        keyValueView = keyVal
        leftCol.addView(keyRow)

        val (pairRow, _) = createActionRow("Pair with Phone (QR Code)", "Open temporary local web form") {
            toggleLanPairing()
        }
        leftCol.addView(pairRow)

        val (testRow, _) = createActionRow("Test Connection", "Verify server ping & API key") {
            runConnectionTest()
        }
        leftCol.addView(testRow)

        val (sourceRow, sourceVal) = createActionRow("Photo Source", "") {
            val modes = ImmichSourceMode.entries
            val next = modes[(modes.indexOf(workingConfig.sourceMode) + 1) % modes.size]
            workingConfig = workingConfig.copy(sourceMode = next)
            refreshLabels()
        }
        sourceModeValueView = sourceVal
        leftCol.addView(sourceRow)

        val (albumsRow, albumsVal) = createActionRow("Select Albums", "") {
            openAlbumPicker()
        }
        albumsValueView = albumsVal
        leftCol.addView(albumsRow)

        val (qualityRow, qualityVal) = createActionRow("Image Quality", "") {
            val next = if (workingConfig.imageQuality == ImmichImageQuality.PREVIEW) {
                ImmichImageQuality.FULLSIZE
            } else {
                ImmichImageQuality.PREVIEW
            }
            workingConfig = workingConfig.copy(imageQuality = next)
            refreshLabels()
        }
        qualityValueView = qualityVal
        leftCol.addView(qualityRow)

        val (landscapeRow, landscapeVal) = createActionRow("Orientation Filter", "") {
            workingConfig = workingConfig.copy(landscapeOnly = !workingConfig.landscapeOnly)
            refreshLabels()
        }
        landscapeValueView = landscapeVal
        leftCol.addView(landscapeRow)

        val (tlsRow, tlsVal) = createActionRow("Allow Self-Signed LAN Certs", "") {
            workingConfig = workingConfig.copy(allowUntrustedTls = !workingConfig.allowUntrustedTls)
            refreshLabels()
        }
        untrustedTlsValueView = tlsVal
        leftCol.addView(tlsRow)

        // Bottom Save & Activate / Cancel row
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val cancelBtn = createButton("Cancel") { dlg.dismiss() }
        val saveBtn = createButton("Save & Activate", primary = true) {
            configStore.save(workingConfig)
            onConfigSaved(workingConfig)
            dlg.dismiss()
        }
        btnBar.addView(cancelBtn)
        btnBar.addView(saveBtn)
        leftCol.addView(btnBar)

        // Right column: QR Phone Pairing Panel
        qrPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        qrImageView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(196), dp(196))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrUrlText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        qrPanel.addView(TextView(context).apply {
            text = "Scan with Phone"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        })
        qrPanel.addView(qrImageView)
        qrPanel.addView(qrUrlText)

        root.addView(leftScroll)
        root.addView(qrPanel)

        dlg.setContentView(root)
        dlg.setOnDismissListener {
            stopLanServer()
            uiScope?.cancel()
            uiScope = null
        }

        refreshLabels()
        dlg.show()
        pairRow.requestFocus()

        // If not configured yet, immediately open QR pairing so setup is effortless!
        if (!workingConfig.isConfigured) {
            toggleLanPairing()
        }
    }

    private fun refreshLabels() {
        urlValueView.text = workingConfig.normalizedServerUrl.ifBlank { "Not set (e.g. http://192.168.1.50:2283)" }
        keyValueView.text = if (workingConfig.apiKey.isBlank()) {
            "Not set"
        } else {
            "••••••••${workingConfig.apiKey.takeLast(4)}"
        }
        sourceModeValueView.text = workingConfig.sourceMode.displayName
        albumsValueView.text = when {
            workingConfig.selectedAlbumIds.isEmpty() -> "None selected (falls back to Random Library)"
            else -> "${workingConfig.selectedAlbumIds.size} album(s) selected"
        }
        qualityValueView.text = workingConfig.imageQuality.displayName
        landscapeValueView.text = if (workingConfig.landscapeOnly) {
            "Landscape only (skip portrait)"
        } else {
            "All orientations (smart blur pillarbox)"
        }
        untrustedTlsValueView.text = if (workingConfig.allowUntrustedTls) {
            "Enabled (homelab self-signed HTTPS)"
        } else {
            "Disabled (strict TLS verification)"
        }
    }

    private fun toggleLanPairing() {
        if (lanServer != null) {
            stopLanServer()
            qrPanel.visibility = View.GONE
            return
        }
        val srv = LanConfigServer(
            initialUrl = workingConfig.normalizedServerUrl,
            initialAllowUntrustedTls = workingConfig.allowUntrustedTls,
            port = 8765,
        ) { url, apiKey, allowUntrusted ->
            uiScope?.launch {
                workingConfig = workingConfig.copy(
                    serverUrl = url,
                    apiKey = apiKey,
                    allowUntrustedTls = allowUntrusted,
                )
                stopLanServer()
                qrPanel.visibility = View.GONE
                refreshLabels()
                showBanner("Credentials received from phone! Testing connection…", "#1E293B", "#E2E8F0")
                runConnectionTest()
            }
        }
        val pairingUrl = runCatching { srv.start() }.getOrElse {
            // Fall back to ephemeral port if 8765 is occupied
            val fallback = LanConfigServer(
                initialUrl = workingConfig.normalizedServerUrl,
                initialAllowUntrustedTls = workingConfig.allowUntrustedTls,
                port = 0,
            ) { url, apiKey, allowUntrusted ->
                uiScope?.launch {
                    workingConfig = workingConfig.copy(
                        serverUrl = url,
                        apiKey = apiKey,
                        allowUntrustedTls = allowUntrusted,
                    )
                    stopLanServer()
                    qrPanel.visibility = View.GONE
                    refreshLabels()
                    showBanner("Credentials received from phone! Testing connection…", "#1E293B", "#E2E8F0")
                    runConnectionTest()
                }
            }
            lanServer = fallback
            runCatching { fallback.start() }.getOrNull()
        }

        if (pairingUrl == null) {
            showBanner("Could not start local pairing server.", "#3B1219", "#FECACA")
            return
        }
        if (lanServer == null) lanServer = srv

        val qrBmp = QrCodeGenerator.generate(pairingUrl, sizePx = 380)
        qrImageView.setImageBitmap(qrBmp)
        val displayUrl = pairingUrl.substringBefore("?pin=")
        qrUrlText.text = "$displayUrl\nPIN: ${lanServer?.pin}"
        qrPanel.visibility = View.VISIBLE
    }

    private fun stopLanServer() {
        runCatching { lanServer?.close() }
        lanServer = null
    }

    private fun runConnectionTest() {
        showBanner("Testing connection to ${workingConfig.normalizedServerUrl}…", "#1E293B", "#CBD5E1")
        uiScope?.launch {
            when (val res = apiClient.testConnection(workingConfig)) {
                is ImmichConnectionResult.Success -> {
                    showBanner(
                        "Connected! Found ${res.albumCount} album(s). Ready to save.",
                        "#123524",
                        "#86EFAC",
                    )
                }

                is ImmichConnectionResult.Failure -> {
                    showBanner(res.message, "#3B1219", "#FECACA")
                }
            }
        }
    }

    private fun openAlbumPicker() {
        if (!workingConfig.isConfigured) {
            showBanner("Enter Server URL and API Key before picking albums.", "#3B1219", "#FECACA")
            return
        }
        showBanner("Loading albums from Immich…", "#1E293B", "#CBD5E1")
        uiScope?.launch {
            val albums: List<ImmichAlbum> = runCatching {
                apiClient.listAlbums(workingConfig)
            }.getOrElse { err ->
                showBanner("Failed to load albums: ${err.message}", "#3B1219", "#FECACA")
                return@launch
            }
            if (albums.isEmpty()) {
                showBanner("No albums found on this Immich account.", "#1E293B", "#FDE68A")
                return@launch
            }
            showAlbumSelectionDialog(albums)
        }
    }

    private fun showAlbumSelectionDialog(albums: List<ImmichAlbum>) {
        val picker = Dialog(context, R.style.Theme_SlateTV_Dialog)
        val selected = workingConfig.selectedAlbumIds.toMutableSet()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_surface)
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        root.addView(TextView(context).apply {
            text = "Select Immich Albums"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(420), dp(320))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        var firstCheck: View? = null
        for (album in albums) {
            val cb = CheckBox(context).apply {
                text = "${album.name} (${album.assetCount})"
                setTextColor(Color.WHITE)
                textSize = 15f
                isChecked = album.id in selected
                background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(album.id) else selected.remove(album.id)
                }
            }
            if (firstCheck == null) firstCheck = cb
            list.addView(cb)
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        bar.addView(createButton("Clear All") {
            selected.clear()
            workingConfig = workingConfig.copy(selectedAlbumIds = emptySet())
            refreshLabels()
            picker.dismiss()
        })
        bar.addView(createButton("Apply", primary = true) {
            workingConfig = workingConfig.copy(
                selectedAlbumIds = selected.toSet(),
                sourceMode = if (selected.isNotEmpty()) ImmichSourceMode.ALBUMS else workingConfig.sourceMode,
            )
            refreshLabels()
            showBanner("${selected.size} album(s) selected.", "#123524", "#86EFAC")
            picker.dismiss()
        })

        root.addView(scroll)
        root.addView(bar)
        picker.setContentView(root)
        picker.show()
        firstCheck?.requestFocus()
    }

    private fun promptTextInput(title: String, current: String, isSecret: Boolean, onDone: (String) -> Unit) {
        val dlg = Dialog(context, R.style.Theme_SlateTV_Dialog)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_surface)
            setPadding(dp(24), dp(22), dp(24), dp(22))
            layoutParams = ViewGroup.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        val edit = EditText(context).apply {
            setText(current)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.parseColor("#141B26"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = if (isSecret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            setSingleLine(true)
        }
        root.addView(edit)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        bar.addView(createButton("Cancel") { dlg.dismiss() })
        bar.addView(createButton("OK", primary = true) {
            onDone(edit.text.toString())
            dlg.dismiss()
        })
        root.addView(bar)
        dlg.setContentView(root)
        dlg.show()
        edit.requestFocus()
    }

    private fun showBanner(message: String, bgHex: String, textHex: String) {
        statusBannerView.text = message
        statusBannerView.setBackgroundColor(Color.parseColor(bgHex))
        statusBannerView.setTextColor(Color.parseColor(textHex))
        statusBannerView.visibility = View.VISIBLE
    }

    private fun createActionRow(title: String, initialValue: String, onClick: () -> Unit): Pair<View, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onClick() }
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        val valueView = TextView(context).apply {
            text = initialValue
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        row.addView(titleView)
        row.addView(valueView)
        return row to valueView
    }

    private fun createButton(label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#CBD5E1"))
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
