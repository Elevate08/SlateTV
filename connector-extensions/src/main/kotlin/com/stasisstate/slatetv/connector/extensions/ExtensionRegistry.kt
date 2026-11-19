package com.stasisstate.slatetv.connector.extensions

import android.content.Context
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPlugin

/**
 * Entry point for `:app`. Exposes the user's enabled extensions as ordinary plugins.
 * Plugin instances are reused while an extension is unchanged so their in-memory caches survive.
 */
class ExtensionRegistry internal constructor(
    private val store: ExtensionStore,
    private val client: ExtensionClient,
) {
    constructor(context: Context) : this(ExtensionStore.create(context), ExtensionClient())

    private val backgrounds = HashMap<String, Pair<InstalledExtension, BackgroundPlugin>>()
    private val shelves = HashMap<String, Pair<InstalledExtension, ShelfPlugin>>()

    fun installedExtensions(): List<InstalledExtension> = store.list()

    @Synchronized
    fun backgroundPlugins(): List<BackgroundPlugin> {
        val wanted = store.list().filter { it.enabled && it.manifest.type.providesBackground && it.manifest.backgroundUrl != null }
        backgrounds.keys.retainAll(wanted.map { it.id }.toSet())
        return wanted.map { ext ->
            val cached = backgrounds[ext.id]
            if (cached != null && cached.first == ext) cached.second else {
                ExtensionBackgroundPlugin(ext, client, { store.apiKey(ext.id) }).also { backgrounds[ext.id] = ext to it }
            }
        }
    }

    @Synchronized
    fun shelfPlugins(): List<ShelfPlugin> {
        val wanted = store.list().filter { it.enabled && it.manifest.type.providesShelf && it.manifest.shelfUrl != null }
        shelves.keys.retainAll(wanted.map { it.id }.toSet())
        return wanted.map { ext ->
            val cached = shelves[ext.id]
            if (cached != null && cached.first == ext) cached.second else {
                ExtensionShelfPlugin(ext, client, { store.apiKey(ext.id) }).also { shelves[ext.id] = ext to it }
            }
        }
    }
}
