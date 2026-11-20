package com.stasisstate.slatetv.connector.homeassistant

import org.json.JSONArray
import org.json.JSONObject

/** An auth provider from `GET /auth/providers`. [id] is null for the built-in "homeassistant" provider. */
data class HaAuthProvider(val type: String, val id: String?, val name: String) {
    val supportsPasswordSignIn: Boolean get() = type == TYPE_HOMEASSISTANT

    companion object {
        const val TYPE_HOMEASSISTANT = "homeassistant"
    }
}

/** One field of a login-flow step, from the voluptuous-serialize field list HA returns as `data_schema`. */
data class HaFormField(
    val name: String,
    val type: Type,
    val required: Boolean,
    /** (value, label) pairs for [Type.SELECT]. */
    val options: List<Pair<String, String>> = emptyList(),
    val default: String? = null,
    val secretFlag: Boolean = false,
) {
    enum class Type { STRING, INTEGER, FLOAT, BOOLEAN, SELECT }

    /** Passwords are plain `str` in HA's schema; the frontend masks them by name, so do the same. */
    val isSecret: Boolean get() = secretFlag || name.contains("password", ignoreCase = true)

    /** One-time codes (TOTP/notify MFA) — numeric keypad on a TV remote. */
    val isOneTimeCode: Boolean get() = name == "code"

    val label: String get() = LABELS[name] ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        private val LABELS = mapOf(
            "username" to "Username",
            "password" to "Password",
            "code" to "Code from your authenticator app",
            "multi_factor_auth_module" to "Two-factor method",
            "user" to "User",
        )
    }
}

/** A step of `POST /auth/login_flow[/{flow_id}]`. */
sealed interface HaFlowStep {
    data class Form(
        val flowId: String,
        val stepId: String,
        val fields: List<HaFormField>,
        /** `errors.base`, e.g. `invalid_auth` or `invalid_code`; null on a fresh step. */
        val error: String?,
        val placeholders: Map<String, String> = emptyMap(),
    ) : HaFlowStep {
        val title: String
            get() = when (stepId) {
                "mfa" -> "Two-factor authentication" +
                    placeholders["mfa_module_name"]?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "select_mfa_module" -> "Choose a two-factor method"
                else -> "Sign in to Home Assistant"
            }

        val errorMessage: String? get() = error?.let(HaLoginText::error)
    }

    /** Finished: [authCode] is redeemed at `/auth/token` with the same client_id. */
    data class Done(val authCode: String) : HaFlowStep

    data class Aborted(val reason: String) : HaFlowStep {
        val message: String get() = HaLoginText.abort(reason)
    }
}

/** Human text for HA's login-flow error/abort keys (HA ships these as frontend translations only). */
object HaLoginText {
    fun error(code: String): String = when (code) {
        "invalid_auth" -> "Wrong username or password. Too many failed attempts make Home Assistant block this TV."
        "invalid_code" -> "That code didn't work. Use the current code from your authenticator app " +
            "(and check the server's clock)."
        "invalid_auth_module" -> "That two-factor method isn't available."
        else -> "Home Assistant said: $code"
    }

    fun abort(reason: String): String = when (reason) {
        "login_expired" -> "The sign-in took too long — start again."
        "too_many_retry" -> "Too many wrong codes — start again."
        "unknown_error" -> "Home Assistant hit an error during sign-in."
        "not_allowed" -> "This sign-in method isn't allowed from this TV's network."
        "no_available_service" -> "No notification service is available for the one-time code."
        else -> "Sign-in stopped: $reason"
    }
}

/** Parsing for the login API's JSON. Pure (org.json only) so it's unit-testable. */
object HaLoginParser {
    fun providers(json: JSONObject): List<HaAuthProvider> {
        val array = json.optJSONArray("providers") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val type = obj.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HaAuthProvider(
                type = type,
                id = obj.nullableString("id"),
                name = obj.optString("name").ifBlank { type },
            )
        }
    }

    fun step(json: JSONObject): HaFlowStep = when (val type = json.optString("type")) {
        "form" -> HaFlowStep.Form(
            flowId = json.optString("flow_id").takeIf { it.isNotBlank() }
                ?: throw HaAuthException("Home Assistant sent a sign-in step without a flow id"),
            stepId = json.optString("step_id").ifBlank { "init" },
            fields = fields(json.optJSONArray("data_schema")),
            error = json.optJSONObject("errors")?.let { errors ->
                errors.nullableString("base") ?: errors.keys().asSequence().firstOrNull()?.let { errors.optString(it) }
            }?.takeIf { it.isNotBlank() },
            placeholders = json.optJSONObject("description_placeholders")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.orEmpty(),
        )
        "create_entry" -> HaFlowStep.Done(
            json.nullableString("result") ?: throw HaAuthException("Home Assistant finished sign-in without a code"),
        )
        "abort" -> HaFlowStep.Aborted(json.optString("reason").ifBlank { "unknown_error" })
        else -> throw HaAuthException("Unsupported sign-in step${type.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}")
    }

    fun fields(array: JSONArray?): List<HaFormField> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val options = options(obj.opt("options"))
            val type = when (obj.optString("type")) {
                "integer" -> HaFormField.Type.INTEGER
                "float" -> HaFormField.Type.FLOAT
                "boolean" -> HaFormField.Type.BOOLEAN
                "select" -> HaFormField.Type.SELECT
                else -> if (options.isNotEmpty()) HaFormField.Type.SELECT else HaFormField.Type.STRING
            }
            HaFormField(
                name = name,
                type = type,
                required = obj.optBoolean("required", false),
                options = options,
                default = obj.nullableString("default"),
                secretFlag = obj.optBoolean("secret", false),
            )
        }
    }

    /** Options are `[[value, label], …]`; tolerate `{value: label}` and bare `[value, …]` too. */
    private fun options(raw: Any?): List<Pair<String, String>> = when (raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { i ->
            when (val item = raw.opt(i)) {
                is JSONArray -> item.opt(0)?.takeUnless { it == JSONObject.NULL }?.toString()?.let { value ->
                    value to (item.opt(1)?.takeUnless { it == JSONObject.NULL }?.toString() ?: value)
                }
                is JSONObject -> item.nullableString("value")?.let { it to (item.nullableString("label") ?: it) }
                null, JSONObject.NULL -> null
                else -> item.toString().let { it to it }
            }
        }
        is JSONObject -> raw.keys().asSequence().map { it to raw.optString(it, it) }.toList()
        else -> emptyList()
    }

    /**
     * Converts what the user typed into the JSON values the step expects. Returns the first problem
     * as a message instead (e.g. a required field left empty) so nothing is sent — every rejected
     * submission counts toward Home Assistant's IP ban.
     */
    fun values(fields: List<HaFormField>, input: Map<String, String>): Result<Map<String, Any>> = runCatching {
        buildMap {
            for (field in fields) {
                val raw = input[field.name].orEmpty().let { if (field.isSecret) it else it.trim() }
                if (raw.isEmpty()) {
                    if (field.required) throw IllegalArgumentException("Enter ${field.label.lowercase()}")
                    continue
                }
                put(field.name, when (field.type) {
                    HaFormField.Type.STRING -> if (field.isOneTimeCode) raw.filterNot(Char::isWhitespace) else raw
                    HaFormField.Type.INTEGER -> raw.toLongOrNull()
                        ?: throw IllegalArgumentException("${field.label} must be a whole number")
                    HaFormField.Type.FLOAT -> raw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("${field.label} must be a number")
                    HaFormField.Type.BOOLEAN -> raw.equals("true", ignoreCase = true)
                    HaFormField.Type.SELECT -> raw.takeIf { value -> field.options.any { it.first == value } }
                        ?: throw IllegalArgumentException("Choose ${field.label.lowercase()}")
                })
            }
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
