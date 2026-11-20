package com.stasisstate.slatetv.connector.homeassistant

import org.json.JSONArray
import org.json.JSONObject

/** A state transition produced by applying one `subscribe_entities` event. */
data class HaStateChange(val old: HaEntity?, val new: HaEntity?)

/**
 * Applies Home Assistant's compressed `subscribe_entities` events (`a` additions, `c` diffs,
 * `r` removals; keys `s`/`a`/`lc`/`lu`) to an in-memory entity map. Pure and thread-confined.
 */
class HaEntityStore {
    private val entities = LinkedHashMap<String, HaEntity>()

    val snapshot: Map<String, HaEntity> get() = LinkedHashMap(entities)

    fun clear() = entities.clear()

    fun apply(event: JSONObject): List<HaStateChange> {
        val changes = ArrayList<HaStateChange>()
        event.optJSONObject("a")?.let { additions ->
            for (entityId in additions.keys()) {
                val compressed = additions.optJSONObject(entityId) ?: continue
                val entity = parseFull(entityId, compressed)
                changes += HaStateChange(entities.put(entityId, entity), entity)
            }
        }
        event.optJSONObject("c")?.let { diffs ->
            for (entityId in diffs.keys()) {
                val diff = diffs.optJSONObject(entityId) ?: continue
                val old = entities[entityId] ?: continue
                val updated = applyDiff(old, diff)
                entities[entityId] = updated
                changes += HaStateChange(old, updated)
            }
        }
        event.optJSONArray("r")?.let { removals ->
            for (i in 0 until removals.length()) {
                val entityId = removals.optString(i).takeIf { it.isNotEmpty() } ?: continue
                entities.remove(entityId)?.let { changes += HaStateChange(it, null) }
            }
        }
        return changes
    }

    private fun parseFull(entityId: String, compressed: JSONObject): HaEntity {
        val lastChanged = compressed.optDouble("lc", 0.0).takeUnless { it.isNaN() } ?: 0.0
        val lastUpdated = compressed.optDouble("lu", Double.NaN).takeUnless { it.isNaN() } ?: lastChanged
        return HaEntity(
            entityId = entityId,
            state = compressed.optString("s", HaEntity.STATE_UNKNOWN),
            attributes = compressed.optJSONObject("a")?.let(::toMap).orEmpty(),
            lastChanged = lastChanged,
            lastUpdated = lastUpdated,
        )
    }

    private fun applyDiff(old: HaEntity, diff: JSONObject): HaEntity {
        var attributes = old.attributes
        diff.optJSONObject("-")?.optJSONArray("a")?.let { removed ->
            val keys = (0 until removed.length()).map { removed.optString(it) }.toSet()
            attributes = attributes.filterKeys { it !in keys }
        }
        val plus = diff.optJSONObject("+") ?: return old.copy(attributes = attributes)
        plus.optJSONObject("a")?.let { changed -> attributes = attributes + toMap(changed) }
        val lc = plus.optDouble("lc", Double.NaN)
        val lu = plus.optDouble("lu", Double.NaN)
        return old.copy(
            state = if (plus.has("s")) plus.optString("s", old.state) else old.state,
            attributes = attributes,
            lastChanged = if (!lc.isNaN()) lc else old.lastChanged,
            lastUpdated = when {
                !lc.isNaN() -> lc
                !lu.isNaN() -> lu
                else -> old.lastUpdated
            },
        )
    }

    companion object {
        fun toMap(json: JSONObject): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for (key in json.keys()) out[key] = unwrap(json.opt(key))
            return out
        }

        private fun unwrap(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> toMap(value)
            is JSONArray -> (0 until value.length()).map { unwrap(value.opt(it)) }
            else -> value
        }
    }
}

/**
 * Decides whether a state transition is a doorbell ring. Conservative by design: false rings are
 * worse than a missed one, so initial snapshots, reconnect replays, and recovery from
 * `unavailable` never ring, and repeats within [debounceMillis] are dropped.
 */
class DoorbellDetector(private val debounceMillis: Long = 12_000L) {
    private val lastRingAt = HashMap<String, Long>()

    fun isRing(change: HaStateChange, nowMillis: Long): Boolean {
        val old = change.old ?: return false
        val new = change.new ?: return false
        if (!new.isAvailable || old.state == HaEntity.STATE_UNAVAILABLE) return false
        val rang = when (new.domain) {
            "binary_sensor" -> old.state == "off" && new.state == "on"
            // event/button/input_button states are the timestamp of the last press/event.
            "event", "button", "input_button" -> old.state != new.state && isFreshTimestamp(new)
            else -> old.state != new.state && new.isOn
        }
        if (!rang) return false
        val previous = lastRingAt[new.entityId]
        if (previous != null && nowMillis - previous in 0 until debounceMillis) return false
        lastRingAt[new.entityId] = nowMillis
        return true
    }

    /**
     * Timestamp-state entities restore their last value after an HA restart. A real press sets the
     * state to (about) the moment HA recorded the change; a restored state is much older than that.
     */
    private fun isFreshTimestamp(entity: HaEntity): Boolean {
        val stateSeconds = parseIsoSeconds(entity.state) ?: return false
        if (entity.lastChanged <= 0.0) return true
        return kotlin.math.abs(entity.lastChanged - stateSeconds) <= FRESH_WINDOW_SECONDS
    }

    companion object {
        private const val FRESH_WINDOW_SECONDS = 120.0

        internal fun parseIsoSeconds(value: String): Double? = runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() / 1000.0
        }.getOrNull()
    }
}
