package com.stasisstate.slatetv.connector.livetv

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class LiveTvConfigMigrationTest {
    @Test fun purgeRemovesEveryLegacyJellyfinKeyAndKeepsCurrentSettings() {
        val prefs = FakePrefs(
            mutableMapOf(
                "server" to "https://jf.example", "encryptedKey" to "abc", "user" to "u", "pinCert" to "der",
                "pinOrigin" to "https://jf.example:443", "sourceMode" to "JELLYFIN_SERVER", "favorites" to true,
                "limit" to 15, "cardTapAction" to "OPEN_GUIDE",
            ),
        )
        assertTrue(purgeLegacyJellyfinPrefs(prefs))
        assertEquals(mapOf<String, Any?>("limit" to 15, "cardTapAction" to "OPEN_GUIDE"), prefs.all)
        assertEquals(1, prefs.commits)
    }

    @Test fun purgeDoesNotWriteWhenNothingLegacyIsPresent() {
        val prefs = FakePrefs(mutableMapOf("limit" to 6))
        assertFalse(purgeLegacyJellyfinPrefs(prefs))
        assertEquals(0, prefs.commits)
        assertEquals(mapOf<String, Any?>("limit" to 6), prefs.all)
    }

    @Test fun legacyKeysCoverTheRemovedJellyfinSource() {
        assertEquals(
            setOf("server", "encryptedKey", "user", "pinCert", "pinOrigin", "sourceMode", "favorites", "launchApp"),
            LEGACY_JELLYFIN_PREF_KEYS.toSet(),
        )
        assertEquals("slatetv_connector_livetv_v1", LEGACY_JELLYFIN_KEY_ALIAS)
        assertEquals(CardTapAction.LAUNCH_CHANNEL, LiveTvConfig().cardTapAction)
        assertEquals(12, LiveTvConfig().maxHomeChannels)
    }

    private class FakePrefs(private val values: MutableMap<String, Any?>) : SharedPreferences {
        var commits = 0

        override fun getAll(): Map<String, *> = values.toMap()
        override fun contains(key: String) = key in values
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val removals = mutableSetOf<String>()
            private val puts = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { removals += values.keys }
            override fun commit(): Boolean {
                removals.forEach(values::remove)
                values.putAll(puts)
                commits++
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
