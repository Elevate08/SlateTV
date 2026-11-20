package com.stasisstate.slatetv.connector.livetv

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AppChannelsConfigStoreTest {
    private val visible = AppChannel.Preview(1, "com.example.tv", "Example", "Popular", true)
    private val hidden = AppChannel.Preview(2, "com.example.tv", "Example", "Continue", false)
    private val watchNext = AppChannel.WatchNext
    private val discovered = listOf(visible, hidden, watchNext)
    private val prefs = inMemoryPreferences()

    @Test fun defaultsFollowNativeChannelVisibilityCapPreviewCountAndUseWatchNextAvailability() {
        val config = AppChannelsConfigStore(prefs).load()
        assertFalse(config.hasUserCustomized)
        assertEquals(setOf(visible.key, watchNext.key), config.enabledKeys(discovered, watchNextHasItems = true))
        assertEquals(setOf(visible.key), config.enabledKeys(discovered, watchNextHasItems = false))
        assertEquals(setOf(visible.key), config.enabledKeys(discovered, watchNextHasItems = null))

        val manyBrowsable = (1L..10L).map { AppChannel.Preview(it, "com.example.tv", "Example", "Ch $it", true) } + watchNext
        assertEquals(
            manyBrowsable.take(MAX_DEFAULT_PREVIEW_CHANNELS).map { it.key }.toSet(),
            config.enabledKeys(manyBrowsable, watchNextHasItems = false),
        )
    }

    @Test fun unknownWatchNextCountDoesNotSnapshotDefaultAsCustomized() {
        val store = AppChannelsConfigStore(prefs)
        val unchanged = store.toggle(hidden, discovered, watchNextHasItems = null)
        assertFalse(unchanged.hasUserCustomized)
        assertFalse(store.load().hasUserCustomized)
    }

    @Test fun firstToggleSavesTheCurrentDefaultsAndThenAppliesTheChange() {
        val store = AppChannelsConfigStore(prefs)
        val updated = store.toggle(hidden, discovered, watchNextHasItems = true)
        assertTrue(updated.hasUserCustomized)
        assertEquals(setOf(visible.key, hidden.key, watchNext.key), updated.enabledChannelKeys)

        val reopened = AppChannelsConfigStore(prefs)
        assertEquals(updated, reopened.load())
        reopened.toggle(visible, discovered, watchNextHasItems = true)
        assertEquals(setOf(hidden.key, watchNext.key), store.load().enabledChannelKeys)
    }

    @Test fun explicitEmptyOptInSurvivesRefreshNewChannelsAndNewWatchNextItems() {
        val store = AppChannelsConfigStore(prefs)
        store.toggle(visible, discovered, watchNextHasItems = false)
        val reopened = AppChannelsConfigStore(prefs).load()
        val newChannel = visible.copy(id = 3)
        assertTrue(reopened.hasUserCustomized)
        assertEquals(emptySet<String>(), reopened.enabledKeys(discovered + newChannel, watchNextHasItems = true))
    }

    @Test fun selectedSourceCanTemporarilyDisappearWithoutLosingItsPreference() {
        val store = AppChannelsConfigStore(prefs)
        store.toggle(hidden, discovered, watchNextHasItems = false)
        store.toggle(watchNext, listOf(visible, watchNext), watchNextHasItems = false)
        assertEquals(setOf(visible.key, hidden.key, watchNext.key), store.load().enabledChannelKeys)
    }

    private fun inMemoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getStringSet", "getBoolean" -> values[args[0] as String] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, Any?>()
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { editor, edit, editArgs ->
                        when (edit.name) {
                            "putStringSet", "putBoolean" -> {
                                pending[editArgs[0] as String] = editArgs[1]
                                editor
                            }
                            "apply" -> values.putAll(pending)
                            else -> error(edit.name)
                        }
                    }
                }
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
