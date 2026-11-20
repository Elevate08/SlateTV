package com.stasisstate.slatetv.connector.homeassistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** App-private prefs (`slatetv_connector_homeassistant`); the token is Keystore-encrypted at rest. */
class HaConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): HaConfig = HaConfig(
        serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
        token = HaSecretCipher.decrypt(prefs.getString(KEY_TOKEN, "").orEmpty()),
        homeEntityIds = decodeStrings(prefs.getString(KEY_HOME_ENTITIES, null)),
        doorbells = decodeDoorbells(prefs.getString(KEY_DOORBELLS, null)),
        quickReplies = prefs.getString(KEY_REPLIES, null)?.let(::decodeReplies) ?: HaConfig.DEFAULT_QUICK_REPLIES,
        alertsEnabled = prefs.getBoolean(KEY_ALERTS_ENABLED, true),
        alertTimeoutSeconds = prefs.getInt(KEY_ALERT_TIMEOUT, 45),
    )

    fun save(config: HaConfig) {
        prefs.edit()
            .putString(KEY_SERVER_URL, config.normalizedServerUrl)
            .putString(KEY_TOKEN, HaSecretCipher.encrypt(config.token.trim()))
            .putString(KEY_HOME_ENTITIES, encodeStrings(config.homeEntityIds))
            .putString(KEY_DOORBELLS, encodeDoorbells(config.doorbells))
            .putString(KEY_REPLIES, encodeReplies(config.quickReplies))
            .putBoolean(KEY_ALERTS_ENABLED, config.alertsEnabled)
            .putInt(KEY_ALERT_TIMEOUT, config.alertTimeoutSeconds)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "slatetv_connector_homeassistant"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_HOME_ENTITIES = "home_entities"
        private const val KEY_DOORBELLS = "doorbells"
        private const val KEY_REPLIES = "quick_replies"
        private const val KEY_ALERTS_ENABLED = "alerts_enabled"
        private const val KEY_ALERT_TIMEOUT = "alert_timeout_seconds"

        internal fun encodeStrings(values: List<String>) = JSONArray(values).toString()

        internal fun decodeStrings(raw: String?): List<String> = runCatching {
            val array = JSONArray(raw ?: return emptyList())
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.distinct()
        }.getOrDefault(emptyList())

        internal fun encodeDoorbells(values: List<DoorbellBinding>) = JSONArray().apply {
            values.forEach { put(JSONObject().put("trigger", it.triggerEntityId).put("camera", it.cameraEntityId ?: "")) }
        }.toString()

        internal fun decodeDoorbells(raw: String?): List<DoorbellBinding> = runCatching {
            val array = JSONArray(raw ?: return emptyList())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val trigger = obj.optString("trigger").takeIf { it.contains('.') } ?: return@mapNotNull null
                DoorbellBinding(trigger, obj.optString("camera").takeIf { it.startsWith("camera.") })
            }.distinctBy { it.triggerEntityId }
        }.getOrDefault(emptyList())

        internal fun encodeReplies(values: List<QuickReply>) = JSONArray().apply {
            values.forEach { put(JSONObject().put("label", it.label).put("action", it.actionEntityId ?: "")) }
        }.toString()

        internal fun decodeReplies(raw: String): List<QuickReply>? = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val label = obj.optString("label").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                QuickReply(label.take(40), obj.optString("action").takeIf { it.contains('.') })
            }.take(HaConfig.MAX_QUICK_REPLIES)
        }.getOrNull()
    }
}
