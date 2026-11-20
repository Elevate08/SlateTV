package com.stasisstate.slatetv.connector.homeassistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 10-foot settings for the Home Assistant integration: server + long-lived access token, which
 * entities appear as home-screen controls, doorbell → camera bindings, quick replies, and alerts.
 * Every change is saved immediately.
 */
class HomeAssistantSettingsActivity : AppCompatActivity() {
    private val runtime = HomeAssistantRuntime
    private val config: HaConfig get() = runtime.config.value

    private lateinit var statusPill: TextView
    private lateinit var rows: LinearLayout
    private var allEntities: List<HaEntity>? = null
    /** Loaded alongside [allEntities] and only trusted while that is non-null. */
    private var registry: HaRegistry = HaRegistry.EMPTY
    private var loadJob: Job? = null
    private var statusOverride: Pair<String, Kind>? = null
    private val discovery by lazy { HaServerDiscovery(this) }
    private var discovered: List<DiscoveredHaServer> = emptyList()
    private val signIn by lazy {
        HaSignInFlow(
            activity = this,
            styleDialog = ::styleDialog,
            status = { message, tone -> showStatus(message, tone.kind) },
            showAlternatives = ::showAdbCommand,
            onSignedIn = { url, result ->
                allEntities = null
                save(config.copy(serverUrl = url, token = result.longLivedToken))
                val version = result.info.version.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                showStatus("Signed in to ${result.info.locationName}$version", Kind.SUCCESS)
            },
        )
    }

    private enum class Kind(val fill: Int, val text: Int) {
        NEUTRAL(0xFF1E293B.toInt(), 0xFFCBD5E1.toInt()),
        WORKING(0xFF0C3A52.toInt(), 0xFF7DD3FC.toInt()),
        SUCCESS(0xFF0F3D2E.toInt(), 0xFF86EFAC.toInt()),
        ERROR(0xFF4A1D24.toInt(), 0xFFFCA5A5.toInt()),
        WARNING(0xFF4A3510.toInt(), 0xFFFCD34D.toInt()),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!runtime.isInitialized) {
            runtime.init(applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Side padding leaves room for the focused row's zoom; clipToPadding must be off
            // or the zoomed row is still cut at its unscaled left and right edges.
            setPadding(dp(16), dp(6), dp(16), dp(24))
            clipToPadding = false
            clipChildren = false
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(48), dp(36), dp(48), dp(24))
        }
        setContentView(screen)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(18))
        }
        header.addView(TextView(this).apply {
            text = "Home Assistant"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(10), dp(20), dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(statusPill, LinearLayout.LayoutParams(-2, -2))
        screen.addView(header)
        screen.addView(ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            isVerticalScrollBarEnabled = false
            addView(rows)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        rebuild()
        lifecycleScope.launch {
            runtime.status.collect { renderStatus() }
        }
        lifecycleScope.launch {
            // Summaries show friendly names; refresh them when the live entity set changes.
            runtime.entities.map { it.keys }.distinctUntilChanged().drop(1).collect { rebuild() }
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start { servers ->
            discovered = servers
            if (::rows.isInitialized) rebuild()
        }
    }

    override fun onStop() {
        discovery.stop()
        discovered = emptyList()
        super.onStop()
    }

    override fun onDestroy() {
        signIn.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the overlay-permission screen: reflect the new grant.
        if (::rows.isInitialized) rebuild()
    }

    // ── Screen ─────────────────────────────────────────────────────────────────────

    private fun rebuild() {
        val focusedKey = (currentFocus?.tag as? String)
        rows.removeAllViews()
        val c = config

        rows.section("CONNECTION")
        rows.row("Server address", c.normalizedServerUrl.ifBlank { "Not set — select to find or type your server" }, "url") {
            chooseServerAddress()
        }
        discovered.filter { it.primaryUrl != c.normalizedServerUrl }.forEach { server ->
            val version = server.version.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            rows.row("Found: ${server.name}", "${server.primaryUrl}$version · Select to use and sign in", "found:${server.key}") {
                useDiscovered(server)
            }
        }
        rows.row(
            if (c.token.isBlank()) "Sign in" else "Sign in again",
            if (c.token.isBlank()) "Use your Home Assistant username and password — the password is never stored"
            else "Signed in · token ends …${c.token.takeLast(4)}. Sign in again to replace it.",
            "signin",
        ) { startSignIn() }
        rows.row("Test connection", "Check the address and token", "test", ::testConnection)

        rows.section("HOME SCREEN CONTROLS")
        // Live entities cover what's subscribed; the full list (once loaded) covers the rest.
        val names = allEntities.orEmpty().associateBy { it.entityId } + runtime.entities.value
        val controlsSummary = if (c.homeEntityIds.isEmpty()) {
            "None — pick lights, switches, scenes or scripts to show above the dock"
        } else {
            val titles = c.homeEntityIds.map { names[it]?.friendlyName ?: it }
            // Same-named controls ("Ceiling", "Ceiling") get their area once the registry is loaded.
            c.homeEntityIds.zip(titles).joinToString(", ") { (id, title) ->
                val area = registry.area(id)?.name
                if (titles.count { it == title } > 1 && area != null) "$title ($area)" else title
            }
        }
        rows.row("Choose home controls", controlsSummary, "controls", ::chooseHomeControls)

        rows.section("DOORBELLS")
        c.doorbells.forEach { binding ->
            val trigger = names[binding.triggerEntityId]?.friendlyName ?: binding.triggerEntityId
            val camera = binding.cameraEntityId?.let { names[it]?.friendlyName ?: it } ?: "No camera"
            rows.row(trigger, "${binding.triggerEntityId} · Camera: $camera", "doorbell:${binding.id}") {
                editDoorbell(binding)
            }
        }
        rows.row("Add doorbell", "Pick the sensor, button, or event that rings, then its camera", "add-doorbell") {
            addDoorbell()
        }

        rows.section("QUICK REPLIES")
        c.quickReplies.forEachIndexed { index, reply ->
            val action = reply.actionEntityId?.let { "Runs ${names[it]?.friendlyName ?: it}" }
                ?: "Fires the ${HomeAssistantRuntime.QUICK_REPLY_EVENT} event (admin token)"
            rows.row("“${reply.label}”", action, "reply:$index") { editReply(index) }
        }
        if (c.quickReplies.size < HaConfig.MAX_QUICK_REPLIES) {
            rows.row("Add quick reply", "Up to ${HaConfig.MAX_QUICK_REPLIES} replies on the doorbell alert", "add-reply") {
                editReply(-1)
            }
        }

        rows.section("ALERTS")
        rows.row("Doorbell alerts", if (c.alertsEnabled) "On" else "Off", "alerts") {
            save(config.copy(alertsEnabled = !config.alertsEnabled))
        }
        rows.row("Auto-dismiss after", "${c.alertTimeoutSeconds} seconds", "timeout") {
            val options = HaConfig.TIMEOUT_OPTIONS
            val next = options[(options.indexOf(config.alertTimeoutSeconds) + 1) % options.size]
            save(config.copy(alertTimeoutSeconds = next))
        }
        val overlay = DoorbellAlertPresenter.canDrawOverlays(this)
        rows.row(
            "Show alerts over other apps",
            if (overlay) "Allowed — alerts float over whatever is playing"
            else "Not allowed — alerts can only appear while Slate TV is on screen. Select to allow.",
            "overlay",
            ::requestOverlayPermission,
        )
        rows.row("Test doorbell alert", testAlertSubtitle(c), "test-alert") {
            val binding = config.doorbells.firstOrNull()
                ?: DoorbellBinding("binary_sensor.slate_tv_test", cameraEntityId = null)
            runtime.simulateRing(binding)
        }

        rows.section("ADVANCED")
        rows.row(
            "Paste an access token",
            if (c.token.isBlank()) "Create one in Home Assistant: Profile → Security → Long-lived access tokens"
            else "Saved (encrypted) · ends …${c.token.takeLast(4)}",
            "token",
            ::pasteToken,
        )
        rows.row("Set up from a computer (adb)", "Send the address and a token over adb — no typing on the TV", "adb", ::showAdbCommand)

        rows.section("DISCONNECT")
        rows.row("Forget this Home Assistant", "Removes the address, token, and all selections", "forget") {
            AlertDialog.Builder(this)
                .setTitle("Forget Home Assistant?")
                .setMessage("Slate TV will remove the saved address, token, controls, doorbells, and replies.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget") { _, _ ->
                    allEntities = null
                    save(HaConfig())
                }
                .create().also(::styleDialog).show()
        }

        renderStatus()
        val target = focusedKey?.let { key -> findTagged(key) } ?: rows.getChildAt(1)
        target?.post { target.requestFocus() }
    }

    private fun testAlertSubtitle(c: HaConfig): String =
        c.doorbells.firstOrNull()?.let { "Shows the alert for ${runtime.ringTitle(it)} now" }
            ?: "Shows a sample alert (add a doorbell to include its camera)"

    private fun findTagged(key: String): View? {
        for (i in 0 until rows.childCount) {
            val child = rows.getChildAt(i)
            if (child.tag == key) return child
        }
        return null
    }

    private fun save(next: HaConfig) {
        runtime.updateConfig(next)
        statusOverride = null
        rebuild()
    }

    private fun showStatus(message: String, kind: Kind) {
        statusOverride = message to kind
        renderStatus()
    }

    private fun renderStatus() {
        if (!::statusPill.isInitialized) return
        val (message, kind) = statusOverride ?: when (val status = runtime.status.value) {
            HaStatus.NotConfigured -> "Setup needed — sign in" to Kind.WARNING
            HaStatus.Connecting -> "Connecting…" to Kind.WORKING
            HaStatus.Connected -> "Connected" to Kind.SUCCESS
            HaStatus.AuthFailed -> "Token rejected — sign in again" to Kind.ERROR
            is HaStatus.Disconnected -> "Offline — retrying in ${status.retryInSeconds}s" to Kind.ERROR
        }
        statusPill.text = "●  $message"
        statusPill.setTextColor(kind.text)
        statusPill.background = GradientDrawable().apply {
            setColor(kind.fill)
            cornerRadius = dp(40).toFloat()
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────────

    private fun testConnection() {
        if (!config.isConfigured) {
            showStatus("Set the server address and sign in first", Kind.WARNING)
            return
        }
        showStatus("Testing…", Kind.WORKING)
        lifecycleScope.launch {
            runCatching { runtime.api().validate() }
                .onSuccess { info ->
                    val version = info.version.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    showStatus("Connected to ${info.locationName}$version", Kind.SUCCESS)
                    runtime.reconnectIfIdle()
                }
                .onFailure { showStatus(it.message ?: "Connection failed", Kind.ERROR) }
        }
    }

    // ── Connection setup ──────────────────────────────────────────────────────────

    private val HaSignInTone.kind: Kind
        get() = when (this) {
            HaSignInTone.NEUTRAL -> Kind.NEUTRAL
            HaSignInTone.WORKING -> Kind.WORKING
            HaSignInTone.SUCCESS -> Kind.SUCCESS
            HaSignInTone.ERROR -> Kind.ERROR
            HaSignInTone.WARNING -> Kind.WARNING
        }

    /** Discovered servers as one-click choices, plus manual entry. */
    private fun chooseServerAddress() {
        val choices = discovered.flatMap { server -> server.urls.map { server to it } }
        if (choices.isEmpty()) return editServerAddress()
        val labels = choices.map { (server, url) -> "${server.name}  ·  $url" } + "Type an address…"
        pick("Server address", labels, choices.indexOfFirst { it.second == config.normalizedServerUrl }) { index ->
            when {
                index >= choices.size -> editServerAddress()
                choices[index].second != config.normalizedServerUrl -> startSignIn(choices[index].second)
            }
        }
    }

    private fun editServerAddress() {
        edit("Server address", config.serverUrl.ifBlank { "http://" }, "http://homeassistant.local:8123", URL_INPUT) { value ->
            val normalized = HaConfig.normalizeServerUrl(value)
            if (value.isNotBlank() && normalized.isBlank()) {
                showStatus("That doesn't look like an http(s) address", Kind.ERROR)
            } else {
                setServer(normalized)
            }
        }
    }

    /**
     * A typed address that differs from the saved one drops the token, so it is only ever sent to
     * the server it was issued for (a typo or look-alike host can't collect it).
     */
    private fun setServer(url: String) {
        if (url == config.normalizedServerUrl) return
        allEntities = null
        val hadToken = config.token.isNotBlank()
        save(config.copy(serverUrl = url, token = ""))
        if (hadToken && url.isNotBlank()) showStatus("Server changed — sign in to connect", Kind.WARNING)
    }

    /**
     * Signs in to a discovered server. Its address is saved only together with the token it issues
     * (see onSignedIn), so a spoofed mDNS answer never receives the saved token.
     */
    private fun useDiscovered(server: DiscoveredHaServer) {
        if (server.urls.size == 1) return startSignIn(server.primaryUrl)
        pick(server.name, server.urls, 0) { startSignIn(server.urls[it]) }
    }

    private fun startSignIn(url: String = config.normalizedServerUrl) {
        if (url.isBlank()) {
            showStatus("Choose your server first", Kind.WARNING)
            chooseServerAddress()
            return
        }
        signIn.start(url)
    }

    private fun pasteToken() {
        edit("Long-lived access token", "", "Paste the token", TOKEN_INPUT) { value ->
            if (value.isNotBlank()) {
                save(config.copy(token = value.trim()))
                allEntities = null
                testConnection()
            }
        }
    }

    private fun showAdbCommand() {
        val command = HaProvisioning.adbCommand(packageName, config.normalizedServerUrl)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(context).apply {
                text = "1. Create a long-lived token in Home Assistant (Profile → Security) and save it in token.txt.\n" +
                    "2. Connect adb to this TV (Developer options → Network debugging).\n3. Run:"
                textSize = 15f
                setTextColor(TEXT_MUTED)
            })
            addView(TextView(context).apply {
                text = command
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(TEXT_PRIMARY)
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(TextView(context).apply {
                text = "Slate TV checks the token with your server, saves it encrypted, and connects. " +
                    "Only adb can send this; other apps on the TV can't."
                textSize = 15f
                setTextColor(TEXT_MUTED)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Set up from a computer")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("OK", null)
            .create().also(::styleDialog).show()
    }

    /** Loads (and caches) all entities plus the registries that label them, then calls [then] on the main thread. */
    private fun withEntities(then: (List<HaEntity>) -> Unit) {
        allEntities?.let { return then(it) }
        if (!config.isConfigured) {
            showStatus("Add the server address and token first", Kind.WARNING)
            return
        }
        loadJob?.cancel()
        showStatus("Loading entities…", Kind.WORKING)
        loadJob = lifecycleScope.launch {
            val api = runtime.api()
            // Registries only improve labels and filtering; any failure degrades to states alone.
            val registryLoad = async { runCatching { api.registry() }.getOrDefault(HaRegistry.EMPTY) }
            runCatching { api.states() }
                .onSuccess { loaded ->
                    registry = registryLoad.await()
                    allEntities = loaded
                    statusOverride = null
                    renderStatus()
                    then(loaded)
                }
                .onFailure {
                    registryLoad.cancel()
                    showStatus(it.message ?: "Couldn't load entities", Kind.ERROR)
                }
        }
    }

    private fun sortedFor(entities: List<HaEntity>, domains: Set<String>, preferred: (HaEntity) -> Boolean = { false }) =
        entities.filter { it.domain in domains }
            .sortedWith(compareBy<HaEntity> { !preferred(it) }.thenBy { it.domain }.thenBy { it.friendlyName.lowercase() })

    private fun label(entity: HaEntity) = "${entity.friendlyName}  ·  ${entity.entityId}"

    private fun chooseHomeControls() = withEntities { entities ->
        val current = config.homeEntityIds
        val items = HaPickerModel.items(entities, registry, HaDomains.HOME_CONTROL, keep = current)
        if (items.isEmpty()) {
            showStatus("No lights, switches, scenes or scripts found", Kind.WARNING)
            return@withEntities
        }
        HaEntityPicker.multi(this, "Home screen controls", items, HaDomains.HOME_CONTROL, current, HaConfig.MAX_HOME_ENTITIES) {
            save(config.copy(homeEntityIds = it.distinct()))
        }
    }

    private fun addDoorbell() = withEntities { entities ->
        val taken = config.doorbells.map { it.triggerEntityId }.toSet()
        val byId = entities.associateBy { it.entityId }
        val items = HaPickerModel.items(entities.filter { it.entityId !in taken }, registry, HaDomains.DOORBELL_TRIGGER)
        if (items.isEmpty()) {
            showStatus("No binary sensors, buttons, or event entities found", Kind.WARNING)
            return@withEntities
        }
        HaEntityPicker.single(
            this, "Which entity means someone rang?", items, HaDomains.DOORBELL_TRIGGER, current = null,
            score = { item -> byId[item.entityId]?.let { HaPickerModel.doorbellScore(it, registry) } ?: 0 },
        ) { trigger ->
            if (trigger == null) return@single
            pickCamera(entities, trigger, null) { camera ->
                save(config.copy(doorbells = config.doorbells + DoorbellBinding(trigger, camera)))
            }
        }
    }

    private fun pickCamera(entities: List<HaEntity>, trigger: String?, current: String?, then: (String?) -> Unit) {
        val byId = entities.associateBy { it.entityId }
        val domains = setOf(HaDomains.CAMERA)
        val items = HaPickerModel.items(entities, registry, domains, keep = listOfNotNull(current))
        HaEntityPicker.single(
            this, "Camera to show live", items, domains, current, noneLabel = "No camera",
            score = { item -> byId[item.entityId]?.let { HaPickerModel.cameraScore(it, trigger, registry) } ?: 0 },
            onPick = then,
        )
    }

    private fun editDoorbell(binding: DoorbellBinding) {
        val options = listOf("Change camera", "Test this doorbell", "Remove")
        pick(runtime.ringTitle(binding), options) { choice ->
            when (choice) {
                0 -> withEntities { entities ->
                    pickCamera(entities, binding.triggerEntityId, binding.cameraEntityId) { camera ->
                        save(config.copy(doorbells = config.doorbells.map {
                            if (it.id == binding.id) it.copy(cameraEntityId = camera) else it
                        }))
                    }
                }
                1 -> runtime.simulateRing(binding)
                2 -> save(config.copy(doorbells = config.doorbells.filterNot { it.id == binding.id }))
            }
        }
    }

    /** Edits reply [index], or adds a new one when index is -1. */
    private fun editReply(index: Int) {
        val existing = config.quickReplies.getOrNull(index)
        if (existing == null) {
            pickReplyLabel(null) { label ->
                save(config.copy(quickReplies = (config.quickReplies + QuickReply(label)).take(HaConfig.MAX_QUICK_REPLIES)))
            }
            return
        }
        fun replace(updated: QuickReply) = save(config.copy(quickReplies = config.quickReplies.toMutableList().also {
            it[index] = updated
        }))
        pick("“${existing.label}”", listOf("Change text", "Choose what it runs in Home Assistant", "Remove")) { choice ->
            when (choice) {
                0 -> pickReplyLabel(existing.label) { replace(existing.copy(label = it)) }
                1 -> withEntities { entities ->
                    val actions = sortedFor(entities, HaDomains.REPLY_ACTION) { it.domain == "script" }
                    val labels = listOf("Nothing — only fire the ${HomeAssistantRuntime.QUICK_REPLY_EVENT} event") +
                        actions.map(::label)
                    val checked = actions.indexOfFirst { it.entityId == existing.actionEntityId } + 1
                    pick("When “${existing.label}” is chosen, run…", labels, checked) { picked ->
                        replace(existing.copy(actionEntityId = if (picked == 0) null else actions[picked - 1].entityId))
                    }
                }
                2 -> save(config.copy(quickReplies = config.quickReplies.filterIndexed { i, _ -> i != index }))
            }
        }
    }

    private fun pickReplyLabel(current: String?, then: (String) -> Unit) {
        val presets = HaConfig.PRESET_REPLY_LABELS
        val labels = presets + "Custom text…"
        pick("Reply text", labels, presets.indexOf(current)) { index ->
            if (index < presets.size) {
                then(presets[index])
            } else {
                edit("Reply text", current.orEmpty(), "e.g. Be right there", TEXT_INPUT) { value ->
                    value.trim().take(40).takeIf { it.isNotEmpty() }?.let(then)
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val opened = try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!opened) {
            AlertDialog.Builder(this)
                .setTitle("Allow alerts over other apps")
                .setMessage(
                    "This TV doesn't offer the \"Display over other apps\" screen. From a computer on the same " +
                        "network, run:\n\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n\n" +
                        "Until then, doorbell alerts open as their own window.",
                )
                .setPositiveButton("OK", null)
                .create().also(::styleDialog).show()
        }
    }

    // ── Dialog + view builders ─────────────────────────────────────────────────────

    private fun pick(title: String, labels: List<String>, checked: Int = -1, then: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                then(which)
            }
            .setNegativeButton("Cancel", null)
            .create().also(::styleDialog).show()
    }

    private fun edit(title: String, value: String, hint: String, inputType: Int, apply: (String) -> Unit) {
        val input = EditText(this).apply {
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            this.hint = hint
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            textSize = 20f
            setText(value)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> apply(input.text.toString().trim()) }
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                apply(input.text.toString().trim())
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        styleDialog(dialog)
        input.requestFocus()
        dialog.show()
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), FOCUSED_FILL)
        })
    }

    private fun LinearLayout.section(label: String) {
        addView(TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setTextColor(TEXT_MUTED)
            setPadding(dp(4), dp(22), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.row(title: String, value: String, key: String, action: () -> Unit) {
        val view = LinearLayout(context).apply {
            tag = key
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                fun shape(color: Int, stroke: Int?) = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(14).toFloat()
                    stroke?.let { setStroke(dp(3), it) }
                }
                addState(intArrayOf(android.R.attr.state_focused), shape(FOCUSED_FILL, FOCUS_RING))
                addState(intArrayOf(android.R.attr.state_pressed), shape(FOCUSED_FILL, FOCUS_RING))
                addState(intArrayOf(), shape(CARD, null))
            }
            setPadding(dp(26), dp(14), dp(26), dp(14))
            minimumHeight = dp(76)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.015f else 1f).scaleY(if (focused) 1.015f else 1f)
                    .setDuration(120).start()
            }
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT_PRIMARY)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 15f
                setTextColor(TEXT_MUTED)
                setPadding(0, dp(4), 0, 0)
            })
        }
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val URL_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private const val TOKEN_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private const val TEXT_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        private val BG = Color.parseColor("#0A0D12")
        private val CARD = Color.parseColor("#141923")
        private val FOCUSED_FILL = Color.parseColor("#1B2332")
        private val FOCUS_RING = Color.parseColor("#F8FAFC")
        private val TEXT_PRIMARY = Color.parseColor("#F8FAFC")
        private val TEXT_MUTED = Color.parseColor("#94A3B8")
    }
}
