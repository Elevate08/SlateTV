package com.stasisstate.slatetv.connector.livetv

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyStore

/** What tapping a Live TV channel card on the Home shelf does. */
enum class CardTapAction {
    /** Tune the selected System Live Channels channel directly. Default. */
    LAUNCH_CHANNEL,

    /** Open Slate TV's Live TV guide focused on the channel. */
    OPEN_GUIDE,
}

/**
 * Saved `cardTapAction` → enum. Earlier builds stored a boolean `launchApp` that was written as `false` whenever any
 * Live TV setting was saved, making a saved default indistinguishable from an explicit guide preference. The migration
 * to [CardTapAction] therefore defaults to [CardTapAction.LAUNCH_CHANNEL] once on upgrade while preserving an explicit
 * `OPEN_GUIDE` selection saved under `cardTapAction`.
 */
internal fun parseCardTapAction(saved: String?): CardTapAction =
    if (saved == CardTapAction.OPEN_GUIDE.name) CardTapAction.OPEN_GUIDE else CardTapAction.LAUNCH_CHANNEL

/** Live TV settings. Channels always come from Android TV System Live Channels (no sign-in). */
data class LiveTvConfig(
    val maxHomeChannels: Int = 12,
    val cardTapAction: CardTapAction = CardTapAction.LAUNCH_CHANNEL,
)

/**
 * Preference keys written by builds that supported a Jellyfin server Live TV source (server URL, encrypted API key,
 * user id, TLS pin, source mode) plus the Jellyfin-only "favorites only" toggle. They are never read any more.
 */
internal val LEGACY_JELLYFIN_PREF_KEYS =
    listOf("server", "encryptedKey", "user", "pinCert", "pinOrigin", "sourceMode", "favorites", "launchApp")

/** Cache dir for Live TV channel logos (see [BuiltInLiveTvShelfPlugin]). */
internal const val LIVE_TV_LOGO_DIR = "connector_livetv_logos"

/** AndroidKeyStore alias that encrypted the old Jellyfin API key. */
internal const val LEGACY_JELLYFIN_KEY_ALIAS = "slatetv_connector_livetv_v1"

/**
 * Removes [LEGACY_JELLYFIN_PREF_KEYS] from [prefs]. Writes only when at least one key is present, so it is cheap to call
 * on every load. Returns true when anything was removed.
 */
internal fun purgeLegacyJellyfinPrefs(prefs: SharedPreferences): Boolean {
    val present = LEGACY_JELLYFIN_PREF_KEYS.filter(prefs::contains)
    if (present.isEmpty()) return false
    prefs.edit().apply { present.forEach(::remove) }.commit()
    return true
}

class LiveTvConfigStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Removes all state of the removed Jellyfin server source. Call once at app startup (off the main thread) so it runs
     * even when the Live TV shelf is disabled; load/save also call it.
     */
    fun migrateLegacyState() = synchronized(lock) { dropLegacyJellyfinState() }

    fun load(): LiveTvConfig = synchronized(lock) {
        dropLegacyJellyfinState()
        LiveTvConfig(
            maxHomeChannels = prefs.getInt("limit", 12),
            cardTapAction = parseCardTapAction(prefs.getString(CARD_TAP_ACTION, null)),
        )
    }

    fun save(config: LiveTvConfig) = synchronized(lock) {
        dropLegacyJellyfinState()
        check(prefs.edit()
            .putInt("limit", config.maxHomeChannels)
            .putString(CARD_TAP_ACTION, config.cardTapAction.name)
            .remove(LEGACY_LAUNCH_APP)
            .commit())
    }

    /** Best-effort cleanup of the removed Jellyfin server source: old prefs once present, the KeyStore alias once per process. */
    private fun dropLegacyJellyfinState() {
        val removed = runCatching { purgeLegacyJellyfinPrefs(prefs) }.getOrDefault(false)
        if (removed) {
            // Logos cached for the old Jellyfin server; System Live Channels logos are rebuilt on the next refresh.
            runCatching { java.io.File(appContext.cacheDir, LIVE_TV_LOGO_DIR).deleteRecursively() }
        }
        if (keyStoreChecked) return
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(LEGACY_JELLYFIN_KEY_ALIAS)) store.deleteEntry(LEGACY_JELLYFIN_KEY_ALIAS)
            keyStoreChecked = true
        }
    }

    private companion object {
        const val PREFS = "slatetv_connector_livetv"
        const val CARD_TAP_ACTION = "cardTapAction"

        /** Pre-enum boolean; superseded by [CARD_TAP_ACTION] and removed on the next save. */
        const val LEGACY_LAUNCH_APP = "launchApp"
        val lock = Any()

        @Volatile
        var keyStoreChecked = false
    }
}
