package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class HaArea(val id: String, val name: String)

data class HaDevice(
    val id: String,
    val name: String,
    val areaId: String? = null,
    val model: String = "",
    val manufacturer: String = "",
)

/** The display-relevant part of an entity registry entry (`config/entity_registry/list_for_display`). */
data class HaRegistryEntry(
    val entityId: String,
    val name: String? = null,
    val deviceId: String? = null,
    /** Set only when the entity overrides its device's area. */
    val areaId: String? = null,
    /** `config` or `diagnostic`, or null for primary entities. */
    val category: String? = null,
    val hidden: Boolean = false,
    val platform: String = "",
    val translationKey: String? = null,
)

/**
 * Home Assistant's area, device, and entity registries, used only to label and filter entities in
 * the settings pickers. Every lookup tolerates missing data: YAML entities without a unique_id have
 * no registry entry, and a server that refuses a registry call yields [EMPTY] for that part.
 */
data class HaRegistry(
    val areas: Map<String, HaArea> = emptyMap(),
    val devices: Map<String, HaDevice> = emptyMap(),
    val entries: Map<String, HaRegistryEntry> = emptyMap(),
) {
    val isEmpty: Boolean get() = areas.isEmpty() && devices.isEmpty() && entries.isEmpty()

    fun entry(entityId: String): HaRegistryEntry? = entries[entityId]

    fun device(entityId: String): HaDevice? = entries[entityId]?.deviceId?.let(devices::get)

    /** The entity's own area, else its device's. */
    fun area(entityId: String): HaArea? {
        val entry = entries[entityId] ?: return null
        val areaId = entry.areaId ?: entry.deviceId?.let(devices::get)?.areaId ?: return null
        return areas[areaId]
    }

    /** Hidden entities and config/diagnostic ones are left out of pickers unless asked for. */
    fun isSecondary(entityId: String): Boolean {
        val entry = entries[entityId] ?: return false
        return entry.hidden || entry.category != null
    }

    companion object {
        val EMPTY = HaRegistry()

        /** Parses the `list_for_display` result: `{entity_categories: {"0": "config"}, entities: [{ei, en, di, ai, ec, hb, ...}]}`. */
        fun parseEntities(result: JSONObject?): Map<String, HaRegistryEntry> {
            if (result == null) return emptyMap()
            val categories = result.optJSONObject("entity_categories")
            val list = result.optJSONArray("entities") ?: return emptyMap()
            return objects(list).mapNotNull { obj ->
                val entityId = obj.optString("ei").takeIf { it.contains('.') } ?: return@mapNotNull null
                val category = if (obj.has("ec") && !obj.isNull("ec")) {
                    val key = obj.opt("ec").toString()
                    categories?.optString(key)?.takeIf { it.isNotBlank() } ?: "category:$key"
                } else {
                    null
                }
                HaRegistryEntry(
                    entityId = entityId,
                    name = obj.text("en"),
                    deviceId = obj.text("di"),
                    areaId = obj.text("ai"),
                    category = category,
                    hidden = obj.optBoolean("hb", false),
                    platform = obj.optString("pl"),
                    translationKey = obj.text("tk"),
                )
            }.associateBy { it.entityId }
        }

        fun parseAreas(result: JSONArray?): Map<String, HaArea> =
            objects(result).mapNotNull { obj ->
                val id = obj.text("area_id") ?: return@mapNotNull null
                HaArea(id, obj.text("name") ?: id)
            }.associateBy { it.id }

        fun parseDevices(result: JSONArray?): Map<String, HaDevice> =
            objects(result).mapNotNull { obj ->
                val id = obj.text("id") ?: return@mapNotNull null
                HaDevice(
                    id = id,
                    name = obj.text("name_by_user") ?: obj.text("name") ?: "",
                    areaId = obj.text("area_id"),
                    model = obj.text("model").orEmpty(),
                    manufacturer = obj.text("manufacturer").orEmpty(),
                )
            }.associateBy { it.id }

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}

/**
 * Fetches the registries over a short-lived WebSocket. These list commands don't require an admin
 * user, but each is optional: a failed or slow call just leaves that part of the registry empty,
 * and the pickers fall back to `/api/states` names.
 */
internal object HaRegistryLoader {
    const val ENTITIES = "config/entity_registry/list_for_display"
    const val AREAS = "config/area_registry/list"
    const val DEVICES = "config/device_registry/list"

    suspend fun load(
        api: HaApiClient,
        token: String,
        client: OkHttpClient = HaApiClient.webSocketClient,
        timeoutMs: Long = 8_000,
    ): HaRegistry {
        val messages = Channel<String?>(Channel.UNLIMITED)
        val socket = client.newWebSocket(api.webSocketRequest(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.trySend(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messages.trySend(null)
            }
        })
        val commands = mapOf(1 to ENTITIES, 2 to AREAS, 3 to DEVICES)
        val results = mutableMapOf<String, Any?>()
        try {
            withTimeoutOrNull(timeoutMs) {
                while (results.size < commands.size) {
                    val text = messages.receive() ?: break
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    when (message.optString("type")) {
                        "auth_required" -> socket.send(
                            JSONObject().put("type", "auth").put("access_token", token).toString(),
                        )
                        "auth_invalid" -> break
                        "auth_ok" -> commands.forEach { (id, type) ->
                            socket.send(JSONObject().put("id", id).put("type", type).toString())
                        }
                        "result" -> {
                            val type = commands[message.optInt("id")] ?: continue
                            results[type] = if (message.optBoolean("success")) message.opt("result") else null
                        }
                    }
                }
            }
        } finally {
            socket.cancel()
            messages.close()
        }
        return HaRegistry(
            areas = HaRegistry.parseAreas(results[AREAS] as? JSONArray),
            devices = HaRegistry.parseDevices(results[DEVICES] as? JSONArray),
            entries = HaRegistry.parseEntities(results[ENTITIES] as? JSONObject),
        )
    }
}
