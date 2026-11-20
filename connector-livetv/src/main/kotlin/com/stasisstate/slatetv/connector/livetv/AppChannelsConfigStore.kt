package com.stasisstate.slatetv.connector.livetv

import android.content.Context
import android.content.SharedPreferences

internal const val MAX_DEFAULT_PREVIEW_CHANNELS = 6

internal data class AppChannelsConfig(
    val enabledChannelKeys: Set<String> = emptySet(),
    val hasUserCustomized: Boolean = false,
) {
    fun enabledKeys(discovered: List<AppChannel>, watchNextHasItems: Boolean?): Set<String> {
        if (hasUserCustomized) return enabledChannelKeys
        val defaults = linkedSetOf<String>()
        discovered.filterIsInstance<AppChannel.Preview>()
            .filter { it.browsable }
            .take(MAX_DEFAULT_PREVIEW_CHANNELS)
            .mapTo(defaults, AppChannel.Preview::key)
        if (watchNextHasItems == true && discovered.any { it == AppChannel.WatchNext }) {
            defaults += AppChannel.WatchNext.key
        }
        return defaults
    }
}

internal class AppChannelsConfigStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("slatetv_app_channels", Context.MODE_PRIVATE))

    fun load(): AppChannelsConfig = synchronized(lock) {
        AppChannelsConfig(
            enabledChannelKeys = prefs.getStringSet(ENABLED_KEYS, emptySet()).orEmpty().toSet(),
            hasUserCustomized = prefs.getBoolean(CUSTOMIZED, false),
        )
    }

    fun toggle(channel: AppChannel, discovered: List<AppChannel>, watchNextHasItems: Boolean?): AppChannelsConfig =
        synchronized(lock) {
            val current = load()
            if (!current.hasUserCustomized && watchNextHasItems == null) {
                return@synchronized current
            }
            val enabled = current.enabledKeys(discovered, watchNextHasItems).toMutableSet()
            if (!enabled.remove(channel.key)) enabled.add(channel.key)
            prefs.edit().putStringSet(ENABLED_KEYS, enabled.toSet()).putBoolean(CUSTOMIZED, true).apply()
            AppChannelsConfig(enabled, hasUserCustomized = true)
        }

    private companion object {
        const val ENABLED_KEYS = "enabledChannelKeys"
        const val CUSTOMIZED = "hasUserCustomized"
        val lock = Any()
    }
}
