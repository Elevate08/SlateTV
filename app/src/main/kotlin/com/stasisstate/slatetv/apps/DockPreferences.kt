package com.stasisstate.slatetv.apps

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists ordered pinned dock packages and hidden packages.
 */
interface DockStorage {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class SharedPrefsDockStorage(context: Context) : DockStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("slate_dock_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

class DockPreferences(private val storage: DockStorage) {

    constructor(context: Context) : this(SharedPrefsDockStorage(context))

    fun getPinnedPackages(): List<String> {
        val raw = storage.getString(KEY_PINNED, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun setPinnedPackages(packages: List<String>) {
        storage.putString(KEY_PINNED, packages.distinct().joinToString(","))
        storage.putBoolean(KEY_INITIALIZED, true)
    }

    fun getHiddenPackages(): Set<String> {
        val raw = storage.getString(KEY_HIDDEN, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setHiddenPackages(packages: Set<String>) {
        storage.putString(KEY_HIDDEN, packages.joinToString(","))
    }

    fun pin(packageName: String) {
        val current = getPinnedPackages().toMutableList()
        if (!current.contains(packageName)) {
            current.add(packageName)
            setPinnedPackages(current)
        }
        unhide(packageName)
    }

    fun unpin(packageName: String) {
        val current = getPinnedPackages().toMutableList()
        if (current.remove(packageName)) {
            setPinnedPackages(current)
        }
    }

    fun movePinned(packageName: String, delta: Int): Boolean {
        val current = getPinnedPackages().toMutableList()
        val index = current.indexOf(packageName)
        if (index == -1) return false
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return false
        val item = current.removeAt(index)
        current.add(target, item)
        setPinnedPackages(current)
        return true
    }

    fun hide(packageName: String) {
        unpin(packageName)
        val hidden = getHiddenPackages().toMutableSet()
        hidden.add(packageName)
        setHiddenPackages(hidden)
    }

    fun unhide(packageName: String) {
        val hidden = getHiddenPackages().toMutableSet()
        if (hidden.remove(packageName)) {
            setHiddenPackages(hidden)
        }
    }

    /**
     * On very first launch, automatically pins up to 6 common TV apps that are installed
     * so the home dock feels immediately ready out of the box.
     */
    fun seedDefaultsIfNeeded(installedPackages: List<String>) {
        if (storage.getBoolean(KEY_INITIALIZED, false)) return
        val installedSet = installedPackages.toSet()
        val seeded = mutableListOf<String>()
        for (preferred in POPULAR_TV_PACKAGES) {
            if (preferred in installedSet && seeded.size < MAX_INITIAL_PINS) {
                seeded += preferred
            }
        }
        if (seeded.size < 4) {
            for (pkg in installedPackages) {
                if (pkg !in seeded && seeded.size < 4) {
                    seeded += pkg
                }
            }
        }
        setPinnedPackages(seeded)
    }

    companion object {
        private const val KEY_PINNED = "pinned_packages"
        private const val KEY_HIDDEN = "hidden_packages"
        private const val KEY_INITIALIZED = "dock_initialized"
        private const val MAX_INITIAL_PINS = 6

        private val POPULAR_TV_PACKAGES = listOf(
            "com.plexapp.android",
            "org.jellyfin.androidtv",
            "com.teamsmart.videomanager.tv", // SmartTube
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.amazon.amazonvideo.livingroom",
            "com.stremio.one",
            "org.xbmc.kodi",
            "com.spotify.tv.android",
            "com.nvidia.tegrazone3",
            "com.android.tv.settings",
        )
    }
}
