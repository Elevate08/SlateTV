package com.stasisstate.slatetv.connector.homeassistant

import java.net.URI
import kotlin.math.roundToInt

/** A doorbell: the entity whose activity means "someone rang", plus the camera to show live. */
data class DoorbellBinding(
    val triggerEntityId: String,
    val cameraEntityId: String? = null,
) {
    val id: String get() = triggerEntityId
}

/**
 * A quick reply offered on the doorbell alert. When [actionEntityId] is set, selecting it runs that
 * entity's natural action (script/scene/button/automation/etc.). Every reply also fires the
 * `slatetv_quick_reply` event on a best-effort basis (admin tokens only) so automations can react.
 */
data class QuickReply(
    val label: String,
    val actionEntityId: String? = null,
)

data class HaConfig(
    val serverUrl: String = "",
    val token: String = "",
    val homeEntityIds: List<String> = emptyList(),
    val doorbells: List<DoorbellBinding> = emptyList(),
    val quickReplies: List<QuickReply> = DEFAULT_QUICK_REPLIES,
    val alertsEnabled: Boolean = true,
    val alertTimeoutSeconds: Int = 45,
) {
    val normalizedServerUrl: String get() = normalizeServerUrl(serverUrl)
    val isConfigured: Boolean get() = normalizedServerUrl.isNotBlank() && token.isNotBlank()

    companion object {
        val DEFAULT_QUICK_REPLIES = listOf(
            QuickReply("Be right there"),
            QuickReply("Leave it at the door"),
            QuickReply("No thanks"),
        )
        val PRESET_REPLY_LABELS = listOf(
            "Be right there",
            "Leave it at the door",
            "No thanks",
            "Just a moment",
            "Come in",
            "Please wait",
            "We're not home",
        )
        const val MAX_QUICK_REPLIES = 4
        const val MAX_HOME_ENTITIES = 12
        val TIMEOUT_OPTIONS = listOf(20, 45, 90, 180)

        /**
         * Accepts `homeassistant.local:8123`, `http://10.0.0.5:8123/`, or a full URL with a trailing
         * `/api` and returns `scheme://host[:port][/path]` with no trailing slash, or "" if invalid.
         */
        fun normalizeServerUrl(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.contains("://")) value = "http://$value"
            val uri = runCatching { URI(value) }.getOrNull() ?: return ""
            val scheme = uri.scheme?.lowercase() ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            val host = uri.host ?: return ""
            if (uri.userInfo != null) return ""
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/api").trimEnd('/')
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "$scheme://$hostPart$port$path"
        }
    }
}

/** A Home Assistant entity state as tracked from the `subscribe_entities` stream. */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap(),
    val lastChanged: Double = 0.0,
    val lastUpdated: Double = 0.0,
) {
    val domain: String get() = entityId.substringBefore('.')
    val friendlyName: String
        get() = (attributes["friendly_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: entityId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
    val isAvailable: Boolean get() = state != STATE_UNAVAILABLE && state != STATE_UNKNOWN
    val isOn: Boolean get() = state in ON_STATES

    /** Light brightness 0..100, or null when the light is off or doesn't report brightness. */
    val brightnessPercent: Int?
        get() {
            if (domain != "light" || !isOn) return null
            val raw = (attributes["brightness"] as? Number)?.toDouble() ?: return null
            return ((raw / 255.0) * 100.0).roundToInt().coerceIn(1, 100)
        }

    val supportsBrightness: Boolean
        get() {
            if (domain != "light") return false
            val modes = attributes["supported_color_modes"] as? List<*> ?: return attributes.containsKey("brightness")
            return modes.any { it != "onoff" }
        }

    companion object {
        const val STATE_UNAVAILABLE = "unavailable"
        const val STATE_UNKNOWN = "unknown"
        private val ON_STATES = setOf("on", "open", "opening", "unlocked", "playing", "home")
    }
}

/** Which entity domains appear in each settings picker, and what pressing a tile does. */
object HaDomains {
    /** Toggleable/runnable entities that can be pinned to the home screen. */
    val HOME_CONTROL = setOf("light", "switch", "fan", "input_boolean", "scene", "script", "cover")

    /** Entities whose activity counts as a ring. */
    val DOORBELL_TRIGGER = setOf("binary_sensor", "event", "input_button", "button")

    /** Entities a quick reply can run. */
    val REPLY_ACTION = setOf("script", "scene", "button", "input_button", "automation", "switch", "light", "input_boolean")

    const val CAMERA = "camera"

    /** True for momentary entities (run, don't toggle). */
    fun isMomentary(domain: String) = domain == "scene" || domain == "script"

    /**
     * The service call (domain, service, data) that a home-screen tile performs when pressed.
     */
    fun tileAction(entity: HaEntity): ServiceCall {
        val id = entity.entityId
        return when (entity.domain) {
            "scene" -> ServiceCall("scene", "turn_on", mapOf("entity_id" to id))
            "script" -> ServiceCall("script", "turn_on", mapOf("entity_id" to id))
            "cover" -> ServiceCall("cover", "toggle", mapOf("entity_id" to id))
            "light", "switch", "fan", "input_boolean" ->
                ServiceCall(entity.domain, "toggle", mapOf("entity_id" to id))
            else -> ServiceCall("homeassistant", "toggle", mapOf("entity_id" to id))
        }
    }

    /** The service call a quick reply makes against its bound entity. */
    fun replyAction(entityId: String, variables: Map<String, Any?>): ServiceCall {
        val domain = entityId.substringBefore('.')
        return when (domain) {
            "script" -> ServiceCall("script", "turn_on", mapOf("entity_id" to entityId, "variables" to variables))
            "scene" -> ServiceCall("scene", "turn_on", mapOf("entity_id" to entityId))
            "button" -> ServiceCall("button", "press", mapOf("entity_id" to entityId))
            "input_button" -> ServiceCall("input_button", "press", mapOf("entity_id" to entityId))
            "automation" -> ServiceCall("automation", "trigger", mapOf("entity_id" to entityId))
            else -> ServiceCall("homeassistant", "turn_on", mapOf("entity_id" to entityId))
        }
    }

    fun brightnessAction(entityId: String, percent: Int): ServiceCall =
        if (percent <= 0) {
            ServiceCall("light", "turn_off", mapOf("entity_id" to entityId))
        } else {
            ServiceCall("light", "turn_on", mapOf("entity_id" to entityId, "brightness_pct" to percent.coerceIn(1, 100)))
        }
}

data class ServiceCall(val domain: String, val service: String, val data: Map<String, Any?>)

/** One doorbell ring as surfaced to the alert UI. */
data class DoorbellRing(
    val binding: DoorbellBinding,
    val title: String,
    val cameraEntityId: String?,
    val atMillis: Long,
)
