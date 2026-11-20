package com.stasisstate.slatetv.connector.homeassistant

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class HaSignInTone { NEUTRAL, WORKING, SUCCESS, ERROR, WARNING }

/**
 * Username + password sign-in on the TV. Each login-flow step is rendered from the `data_schema`
 * Home Assistant returns (username/password, MFA module choice, one-time code), so TOTP works without
 * special cases. On success a long-lived token is minted (see [HaAuthClient.completeSignIn]) and
 * handed to [onSignedIn]; the password itself is never stored.
 *
 * A step is submitted only from an explicit button/IME action, once — HA counts every rejected
 * attempt toward banning the TV's IP, so nothing here retries automatically.
 */
internal class HaSignInFlow(
    private val activity: AppCompatActivity,
    private val styleDialog: (AlertDialog) -> Unit,
    private val status: (String, HaSignInTone) -> Unit,
    private val showAlternatives: () -> Unit,
    private val onSignedIn: (serverUrl: String, result: HaSignInResult) -> Unit,
) {
    private var job: Job? = null
    private var auth: HaAuthClient? = null
    private var openFlowId: String? = null
    private val remembered = HashMap<String, String>()

    val isRunning: Boolean get() = job?.isActive == true

    fun start(serverUrl: String) {
        if (isRunning) return
        val client = HaAuthClient(serverUrl)
        if (client.baseUrl.isBlank()) {
            status("Set the server address first", HaSignInTone.WARNING)
            return
        }
        if (HaUrlSafety.isInsecureRemote(client.baseUrl)) {
            AlertDialog.Builder(activity)
                .setTitle("Unencrypted connection")
                .setMessage(
                    "${client.baseUrl} uses plain http and doesn't look like a local address, so your " +
                        "password would cross the internet unencrypted.\n\nUse an https address if you have one.",
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign in anyway") { _, _ -> begin(client) }
                .create().also(styleDialog).show()
        } else {
            begin(client)
        }
    }

    /** Stops any request in flight and abandons the server-side flow (e.g. the screen closed). */
    fun cancel() {
        job?.cancel()
        abandonFlow()
    }

    private fun begin(client: HaAuthClient) {
        auth = client
        status("Contacting Home Assistant…", HaSignInTone.WORKING)
        job = activity.lifecycleScope.launch {
            try {
                val providers = client.providers()
                val password = providers.firstOrNull { it.supportsPasswordSignIn }
                if (password == null) {
                    status("Password sign-in isn't enabled on this server", HaSignInTone.WARNING)
                    explainNoPassword(providers)
                    return@launch
                }
                status("Starting sign-in…", HaSignInTone.WORKING)
                handle(client.startFlow(password))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fail(e.message ?: "Couldn't reach Home Assistant")
            }
        }
    }

    private fun handle(step: HaFlowStep) {
        when (step) {
            is HaFlowStep.Form -> {
                openFlowId = step.flowId
                // The pill stays short; the dialog shows the full explanation.
                status(
                    when {
                        step.error == "invalid_code" -> "Code rejected — try again"
                        step.error != null -> "Sign-in failed — try again"
                        step.stepId == "init" -> "Waiting for sign-in"
                        else -> "Two-factor code needed"
                    },
                    if (step.error != null) HaSignInTone.ERROR else HaSignInTone.NEUTRAL,
                )
                showForm(step)
            }
            is HaFlowStep.Done -> {
                openFlowId = null
                finish(step.authCode)
            }
            is HaFlowStep.Aborted -> {
                openFlowId = null
                fail(step.message)
            }
        }
    }

    private fun finish(code: String) {
        val client = auth ?: return
        status("Signed in — creating a Slate TV access token…", HaSignInTone.WORKING)
        job = activity.lifecycleScope.launch {
            try {
                val result = client.completeSignIn(code, deviceName())
                remembered.keys.removeAll { it != "username" }
                onSignedIn(client.baseUrl, result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fail("Signed in, but couldn't create a token: ${e.message}")
            }
        }
    }

    private fun submit(step: HaFlowStep.Form, values: Map<String, Any>) {
        val client = auth ?: return
        status(if (step.stepId == "init") "Signing in…" else "Checking…", HaSignInTone.WORKING)
        job = activity.lifecycleScope.launch {
            try {
                handle(client.submit(step.flowId, values))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                openFlowId = null
                fail(e.message ?: "Sign-in failed")
            }
        }
    }

    /** Short errors go in the status pill; longer explanations also get a dialog. */
    private fun fail(message: String) {
        if (message.length <= 48) return status(message, HaSignInTone.ERROR)
        status("Sign-in failed", HaSignInTone.ERROR)
        AlertDialog.Builder(activity)
            .setTitle("Sign-in failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create().also(styleDialog).show()
    }

    private fun abandonFlow() {
        val flowId = openFlowId ?: return
        val client = auth ?: return
        openFlowId = null
        activity.lifecycleScope.launch { client.cancel(flowId) }
    }

    private fun explainNoPassword(providers: List<HaAuthProvider>) {
        val names = providers.joinToString(", ") { it.name }.ifBlank { "no sign-in methods Slate TV can use" }
        AlertDialog.Builder(activity)
            .setTitle("Password sign-in isn't available")
            .setMessage(
                "This Home Assistant signs in with: $names.\n\nSlate TV can only sign in with a Home Assistant " +
                    "username and password. Instead you can:\n\n" +
                    "• Set it up from a computer with adb (no typing on the TV)\n" +
                    "• Create a long-lived token in Home Assistant (Profile → Security) and paste it under Advanced",
            )
            .setNegativeButton("Close", null)
            .setPositiveButton("Show adb command") { _, _ -> showAlternatives() }
            .create().also(styleDialog).show()
    }

    // ── Form rendering ──────────────────────────────────────────────────────────────

    private fun showForm(step: HaFlowStep.Form) {
        val inputs = LinkedHashMap<String, () -> String>()
        val editors = HashMap<String, EditText>()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        if (step.stepId == "init") {
            content.addView(note("Signing in to ${auth?.baseUrl}\nYour password is used once and never stored."))
        }
        step.errorMessage?.let { content.addView(note(it, ERROR_TEXT)) }
        val textFields = step.fields.filter { it.type != HaFormField.Type.SELECT && it.type != HaFormField.Type.BOOLEAN }
        for (field in step.fields) {
            when (field.type) {
                HaFormField.Type.SELECT -> {
                    content.addView(note(field.label))
                    val group = RadioGroup(activity)
                    field.options.forEachIndexed { index, (value, label) ->
                        group.addView(RadioButton(activity).apply {
                            id = View.generateViewId()
                            tag = value
                            text = label
                            textSize = 20f
                            setTextColor(TEXT_PRIMARY)
                            isChecked = value == (remembered[field.name] ?: field.default) || index == 0 && field.options.size == 1
                        })
                    }
                    if (group.checkedRadioButtonId == View.NO_ID && group.childCount > 0) {
                        (group.getChildAt(0) as RadioButton).isChecked = true
                    }
                    content.addView(group)
                    inputs[field.name] = { group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String ?: "" }
                }
                HaFormField.Type.BOOLEAN -> {
                    val box = CheckBox(activity).apply {
                        text = field.label
                        textSize = 20f
                        setTextColor(TEXT_PRIMARY)
                        isChecked = (remembered[field.name] ?: field.default) == "true"
                    }
                    content.addView(box)
                    inputs[field.name] = { box.isChecked.toString() }
                }
                else -> {
                    val isLast = field == textFields.lastOrNull()
                    val edit = EditText(activity).apply {
                        inputType = inputTypeFor(field)
                        typeface = Typeface.DEFAULT // password input types default to monospace
                        imeOptions = (if (isLast) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT) or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        setSingleLine(true)
                        hint = field.label
                        setHintTextColor(TEXT_MUTED)
                        setTextColor(TEXT_PRIMARY)
                        textSize = 20f
                        if (!field.isSecret) setText(remembered[field.name] ?: field.default.orEmpty())
                        setSelection(text.length)
                    }
                    editors[field.name] = edit
                    content.addView(edit)
                    inputs[field.name] = { edit.text.toString() }
                }
            }
        }
        val action = when (step.stepId) {
            "init" -> "Sign In"
            "mfa" -> "Verify"
            else -> "Continue"
        }
        val dialog = AlertDialog.Builder(activity).setTitle(step.title).setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action, null)
            .create()
        var sent = false
        val trySubmit = submit@{
            if (sent) return@submit
            val typed = inputs.mapValues { it.value() }
            val values = HaLoginParser.values(step.fields, typed)
            val problem = values.exceptionOrNull()
            if (problem != null) {
                val field = step.fields.firstOrNull { typed[it.name].isNullOrBlank() && it.required }
                val editor = field?.let { editors[it.name] }
                if (editor != null) {
                    editor.error = problem.message
                    editor.requestFocus()
                } else {
                    status(problem.message ?: "Check the form", HaSignInTone.WARNING)
                }
                return@submit
            }
            sent = true
            step.fields.filterNot { it.isSecret }.forEach { field -> typed[field.name]?.let { remembered[field.name] = it } }
            step.fields.filter { it.isSecret }.forEach { editors[it.name]?.text?.clear() }
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            submit(step, values.getOrThrow())
        }
        editors.values.forEach { edit ->
            edit.setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO -> {
                        trySubmit()
                        true
                    }
                    else -> false
                }
            }
        }
        // Back / Cancel abandons the flow on the server too.
        dialog.setOnDismissListener {
            if (!sent) {
                abandonFlow()
                status("Sign-in cancelled", HaSignInTone.NEUTRAL)
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        styleDialog(dialog)
        val firstEmpty = step.fields.mapNotNull { editors[it.name] }.firstOrNull { it.text.isEmpty() }
            ?: editors.values.firstOrNull()
        firstEmpty?.requestFocus()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { trySubmit() }
    }

    private fun inputTypeFor(field: HaFormField): Int = when {
        field.isSecret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        field.isOneTimeCode || field.type == HaFormField.Type.INTEGER -> InputType.TYPE_CLASS_NUMBER
        field.type == HaFormField.Type.FLOAT -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        field.name == "username" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD // no autocaps/autocorrect on usernames
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun note(text: String, color: Int = TEXT_MUTED) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun deviceName(): String {
        val named = runCatching { Settings.Global.getString(activity.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return named?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        val TEXT_PRIMARY = Color.parseColor("#F8FAFC")
        val TEXT_MUTED = Color.parseColor("#94A3B8")
        val ERROR_TEXT = Color.parseColor("#FCA5A5")
    }
}
