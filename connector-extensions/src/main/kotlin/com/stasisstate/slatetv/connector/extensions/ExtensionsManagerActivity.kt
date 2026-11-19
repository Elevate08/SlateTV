package com.stasisstate.slatetv.connector.extensions

import android.app.Dialog
import android.content.Context
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
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** Dark architectural palette for the 10-foot UI. */
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

/**
 * Manage extensions: add by phone QR code or URL (always behind an explicit confirmation),
 * enable/disable, test, set/clear API key, remove. Opened only via explicit intent.
 */
class ExtensionsManagerActivity : AppCompatActivity() {
    private class Row(val view: LinearLayout, val title: TextView, val value: TextView)

    private val scope = MainScope()
    private lateinit var store: ExtensionStore
    private lateinit var client: ExtensionClient

    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var addQrRow: Row

    private var activeDialog: Dialog? = null
    private var pairing: PairingServer? = null
    private var pairingDialog: Dialog? = null
    private var job: Job? = null
    private var testJob: Job? = null
    private var focusAfterRefresh: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = ExtensionStore.create(applicationContext)
        client = ExtensionClient()

        val content = column().apply {
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(dp(56), dp(32), dp(56), dp(40))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BACKGROUND)
            addView(content)
        })

        content.addView(text("Extensions", 32f, Ui.TEXT, bold = true))
        content.addView(text("Add community photo feeds and home shelves from any web address. Extensions only supply images and cards. They never run code on this device.", 17f, Ui.MUTED).apply {
            setPadding(0, dp(4), 0, dp(16))
        })
        statusView = text("", 17f, Ui.OK).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            setPadding(dp(4), 0, 0, dp(8))
        }
        content.addView(statusView)

        content.addView(sectionHeader("ADD AN EXTENSION"))
        addQrRow = row("Add Extension via Phone QR Code", "Scan a code, then paste the manifest address on your phone.") { showPairing() }
        content.addView(addQrRow.view, rowParams())
        content.addView(row("Add Extension by URL", "Type the manifest address with the TV keyboard.") { promptForUrl() }.view, rowParams())

        content.addView(sectionHeader("INSTALLED EXTENSIONS"))
        listContainer = column()
        content.addView(listContainer)

        refreshList()
        addQrRow.view.requestFocus()
    }

    // ---------------------------------------------------------------- list

    private fun refreshList() {
        listContainer.removeAllViews()
        val installed = store.list()
        if (installed.isEmpty()) {
            listContainer.addView(text("No extensions installed yet.", 17f, Ui.MUTED).apply { setPadding(dp(4), dp(4), 0, dp(8)) })
            return
        }
        var focusTarget: View? = null
        installed.forEach { ext ->
            val m = ext.manifest
            val host = ExtensionUrlPolicy.parseHttpUrl(m.manifestUrl)?.host.orEmpty()
            val parts = listOf(
                typeLabel(m.type), host, "v${m.version}",
                if (m.manifestUrl.startsWith("https://")) "HTTPS" else "HTTP (local network)",
                if (ext.hasApiKey) "API key set" else null,
            ).filterNotNull().joinToString(" · ")
            val row = row(if (ext.enabled) m.name else "${m.name} (disabled)", parts) { showActions(ext.id) }
            row.title.setTextColor(if (ext.enabled) Ui.TEXT else Ui.MUTED)
            listContainer.addView(row.view, rowParams())
            if (ext.id == focusAfterRefresh) focusTarget = row.view
        }
        focusAfterRefresh = null
        focusTarget?.requestFocus()
    }

    private fun setStatus(message: String, color: Int = Ui.OK) {
        statusView.text = message
        statusView.setTextColor(color)
        statusView.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun typeLabel(type: ExtensionType) = when (type) {
        ExtensionType.BACKGROUND -> "Backgrounds"
        ExtensionType.SHELF -> "Home shelf"
        ExtensionType.BOTH -> "Backgrounds + shelf"
    }

    // ---------------------------------------------------------------- add flows

    private fun promptForUrl() {
        editDialog(
            title = "Add Extension by URL",
            hint = "https://example.com/manifest.json",
            initial = "",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            maxLength = ExtensionUrlPolicy.MAX_URL_LENGTH,
            validate = {
                if (ExtensionUrlPolicy.validateManifestUrl(it) == null) {
                    "Enter an https:// address. http:// works only for addresses on your local network."
                } else null
            },
        ) { value -> startPreview(value, null) }
    }

    /** Fetches the manifest and shows the confirmation dialog. Nothing is saved before the user confirms. */
    private fun startPreview(url: String, apiKey: String?) {
        setStatus("Checking extension…", Ui.MUTED)
        job?.cancel()
        job = scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { client.fetchManifest(url, apiKey) }
                setStatus("")
                showInstallPreview(manifest, apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExtensionException) {
                if ((e.status == 401 || e.status == 403) && apiKey == null) {
                    setStatus("This extension needs an API key.", Ui.WARN)
                    editDialog(
                        title = "API key required",
                        hint = "API key or token",
                        initial = "",
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                        maxLength = 512,
                        validate = { if (!AuthHeader.validKey(it)) "Enter a valid API key (visible characters only)." else null },
                    ) { key -> startPreview(url, key) }
                } else {
                    setStatus(e.message ?: "Could not read the extension.", Ui.BAD)
                }
            } catch (e: Exception) {
                setStatus("Could not read the extension.", Ui.BAD)
            }
        }
    }

    private fun showInstallPreview(manifest: ExtensionManifest, apiKey: String?) {
        val manifestUrl = ExtensionUrlPolicy.parseHttpUrl(manifest.manifestUrl)
        val secure = manifest.manifestUrl.startsWith("https://")
        val existing = store.get(manifest.id)
        val body = column().apply {
            if (manifest.autoDetected) {
                addView(text("Plain photo feed detected. Slate TV will use it as a background source.", 16f, Ui.WARN).apply {
                    setPadding(0, 0, 0, dp(8))
                })
            }
            addView(detail("Name", manifest.name))
            addView(detail("Author", manifest.author))
            addView(detail("Version", manifest.version))
            addView(detail("Host", manifestUrl?.let { ExtensionUrlPolicy.originOf(it).substringAfter("://") } ?: "unknown"))
            addView(detail("Type", typeLabel(manifest.type)))
            addView(detail("Connection", if (secure) "HTTPS (encrypted)" else "HTTP (unencrypted, local network only)", if (secure) Ui.OK else Ui.WARN))
            addView(detail("API key", if (apiKey != null) "Provided, stored encrypted" else "None"))
            if (manifest.description.isNotEmpty()) addView(detail("About", manifest.description))
            if (existing != null) addView(text("An extension with this ID is already installed and will be replaced.", 16f, Ui.WARN).apply {
                setPadding(0, dp(8), 0, 0)
            })
            addView(text("Only install extensions from sources you trust.", 15f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }
        lateinit var dialog: Dialog
        dialog = tvDialog(
            "Install this extension?", body,
            "Install" to {
                dialog.dismiss()
                runCatching {
                    store.upsert(InstalledExtension(manifest, enabled = true, installedAtEpochMs = System.currentTimeMillis()), apiKey)
                }.onSuccess {
                    focusAfterRefresh = manifest.id
                    setStatus("Installed ${manifest.name}.")
                }.onFailure { setStatus(it.message ?: "Could not install the extension.", Ui.BAD) }
                refreshList()
            },
            "Cancel" to { dialog.dismiss(); setStatus("Installation cancelled.", Ui.MUTED) },
            initialFocus = 1,
        )
    }

    // ---------------------------------------------------------------- per-extension actions

    private fun showActions(id: String) {
        val ext = store.get(id) ?: return refreshList()
        val result = text("", 16f, Ui.MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(4), dp(8), 0, 0)
        }
        lateinit var dialog: Dialog
        val body = column()
        body.addView(text("${typeLabel(ext.manifest.type)} · ${ext.manifest.author} · v${ext.manifest.version}", 16f, Ui.MUTED).apply {
            setPadding(0, 0, 0, dp(8))
        })
        fun action(label: String, onClick: () -> Unit) = body.addView(row(label, null, centered = true) { onClick() }.view, rowParams())

        action(if (ext.enabled) "Disable" else "Enable") {
            dialog.dismiss()
            store.setEnabled(id, !ext.enabled)
            focusAfterRefresh = id
            setStatus(if (ext.enabled) "${ext.name} disabled." else "${ext.name} enabled.")
            refreshList()
        }
        action("Test Connection") {
            result.setTextColor(Ui.MUTED)
            result.text = "Testing…"
            testJob?.cancel()
            testJob = scope.launch {
                try {
                    val summary = withContext(Dispatchers.IO) { client.test(ext.manifest, store.apiKey(id)) }
                    result.setTextColor(Ui.OK)
                    result.text = summary
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    result.setTextColor(Ui.BAD)
                    result.text = (e as? ExtensionException)?.message ?: "Connection failed."
                }
            }
        }
        action(if (ext.hasApiKey) "Change API Key" else "Set API Key") {
            dialog.dismiss()
            editDialog(
                title = "API key for ${ext.name}",
                hint = "API key or token",
                initial = "",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                maxLength = 512,
                validate = { if (!AuthHeader.validKey(it)) "Enter a valid API key (visible characters only)." else null },
            ) { key ->
                runCatching { store.setApiKey(id, key) }
                    .onSuccess { setStatus("API key saved for ${ext.name}.") }
                    .onFailure { setStatus("Could not save the API key.", Ui.BAD) }
                focusAfterRefresh = id
                refreshList()
            }
        }
        if (ext.hasApiKey) {
            action("Clear API Key") {
                dialog.dismiss()
                store.setApiKey(id, null)
                focusAfterRefresh = id
                setStatus("API key cleared for ${ext.name}.")
                refreshList()
            }
        }
        action("Remove") {
            dialog.dismiss()
            confirmRemove(ext)
        }
        body.addView(result)
        dialog = tvDialog(ext.name, body, "Close" to { dialog.dismiss() })
        dialog.setOnDismissListener { testJob?.cancel(); activeDialog = null }
    }

    private fun confirmRemove(ext: InstalledExtension) {
        lateinit var dialog: Dialog
        dialog = tvDialog(
            "Remove ${ext.name}?",
            text("Its stored API key will be deleted too.", 17f, Ui.MUTED),
            "Remove" to {
                dialog.dismiss()
                store.remove(ext.id)
                setStatus("${ext.name} removed.")
                refreshList()
                addQrRow.view.requestFocus()
            },
            "Cancel" to { dialog.dismiss() },
            initialFocus = 1,
        )
    }

    // ---------------------------------------------------------------- phone pairing

    @Suppress("DEPRECATION") // Scan Wi-Fi and Ethernet even when a VPN is the default network.
    private fun showPairing() {
        stopPairing()
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = manager.allNetworks.asSequence().filter { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .mapNotNull { it.address as? Inet4Address }
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .toList()
        val address = candidates.firstOrNull()
        if (address == null) {
            setStatus("Connect this TV to a local Wi-Fi or Ethernet network to pair a phone.", Ui.WARN)
            return
        }
        lateinit var activeServer: PairingServer
        lateinit var dialog: Dialog
        val server = runCatching {
            PairingServer(address) { manifestUrl, apiKey ->
                runOnUiThread {
                    if (pairingDialog === dialog && pairing === activeServer) {
                        stopPairing()
                        startPreview(manifestUrl, apiKey)
                    }
                }
            }
        }.getOrNull() ?: run {
            setStatus("Could not start phone pairing.", Ui.BAD)
            return
        }
        activeServer = server
        pairing = server
        val body = column().apply {
            addView(text("Scan on the same network · PIN ${server.pin}", 20f, Ui.TEXT, bold = true))
            addView(ImageView(this@ExtensionsManagerActivity).apply {
                setImageBitmap(QrCodeGenerator.generate(server.url))
                contentDescription = "Phone pairing QR code"
            }, LinearLayout.LayoutParams(dp(210), dp(210)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(text(server.url, 16f, Ui.MUTED))
            addView(text("On your phone, enter the PIN and paste the manifest address. You will confirm the extension here before anything is saved.", 15f, Ui.MUTED).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        dialog = tvDialog("Add Extension via Phone", body, "Close" to { dialog.dismiss() })
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
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- dialogs

    private fun tvDialog(
        title: String,
        body: View,
        vararg buttons: Pair<String, () -> Unit>,
        initialFocus: Int = 0,
    ): Dialog {
        activeDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val buttonRows = ArrayList<Row>()
        val card = column().apply {
            background = cardBackground(Ui.CARD, Ui.CARD_SELECTED_BORDER, radius = 16)
            setPadding(dp(28), dp(22), dp(28), dp(22))
            addView(text(title, 24f, Ui.TEXT, bold = true).apply { setPadding(0, 0, 0, dp(12)) })
            addView(body, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(this@ExtensionsManagerActivity).apply { gravity = Gravity.END }
            buttons.forEach { (label, action) ->
                val r = row(label, null, centered = true) { action() }
                buttonRows += r
                actions.addView(r.view, LinearLayout.LayoutParams(dp(180), -2).apply { marginStart = dp(12); topMargin = dp(16) })
            }
            addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(ScrollView(this).apply { addView(card) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
        if (initialFocus > 0) buttonRows.getOrNull(initialFocus)?.view?.requestFocus()
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
            dialog.dismiss()
            onSave(value)
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
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        edit.requestFocus()
        edit.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------------------------------------------------------- views

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun detail(label: String, value: String, valueColor: Int = Ui.TEXT) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        addView(text(label, 16f, Ui.MUTED), LinearLayout.LayoutParams(dp(130), -2))
        addView(text(value, 17f, valueColor, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
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

    private fun cardBackground(
        fill: Int,
        stroke: Int,
        focusStroke: Int = stroke,
        radius: Int = 12,
    ) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(if (fill == Ui.CARD) Ui.CARD_FOCUS else fill, focusStroke, radius, 3))
        addState(intArrayOf(), shape(fill, stroke, radius, 1))
    }

    private fun shape(fill: Int, stroke: Int, radius: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(width), stroke)
    }
}
