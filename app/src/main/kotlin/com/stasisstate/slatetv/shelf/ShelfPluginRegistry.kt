package com.stasisstate.slatetv.shelf

import android.content.Context
import com.stasisstate.slatetv.connector.extensions.ExtensionRegistry
import com.stasisstate.slatetv.connector.extensions.PluginInstallState
import com.stasisstate.slatetv.connector.livetv.BuiltInAppChannelsProvider
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPlugin

/**
 * Registry of built-in Home shelves and installed JSON shelf extensions.
 */
internal class ShelfPluginRegistry(private val discover: () -> List<ShelfPlugin>) {
    constructor(
        context: Context,
        extensionRegistry: ExtensionRegistry = ExtensionRegistry(context.applicationContext),
    ) : this(discovery(context.applicationContext, extensionRegistry))

    private class Snapshot(val plugins: List<ShelfPlugin>, val legacyToCanonical: Map<String, String>)

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyMap())

    val plugins: List<ShelfPlugin> get() = snapshot.plugins

    @Synchronized
    fun refresh(): List<ShelfPlugin> {
        val found = discover()
        val unique = found.distinctBy { it.metadata.id }
        val aliases = buildMap {
            if (unique.any { it.metadata.id == BuiltInLiveTvShelfPlugin.PLUGIN_ID }) {
                put(BuiltInLiveTvShelfPlugin.LEGACY_EXTERNAL_ID, BuiltInLiveTvShelfPlugin.PLUGIN_ID)
            }
        }
        snapshot = Snapshot(unique, aliases)
        return unique
    }

    /** Resolves [id] (canonical or legacy ID) to its canonical ID; unknown IDs pass through. */
    fun canonicalId(id: String): String = resolveCanonicalId(snapshot, id)

    /** Rewrites saved disabled IDs so legacy IDs become canonical built-in IDs. */
    fun migrateDisabledIds(disabledIds: Set<String>): Set<String> {
        val current = snapshot
        return disabledIds.mapTo(LinkedHashSet()) { resolveCanonicalId(current, it) }
    }

    fun isDisabled(pluginId: String, disabledIds: Set<String>): Boolean {
        val current = snapshot
        val canonical = resolveCanonicalId(current, pluginId)
        return disabledIds.any { resolveCanonicalId(current, it) == canonical }
    }

    private fun resolveCanonicalId(current: Snapshot, id: String): String {
        if (current.plugins.any { it.metadata.id == id }) return id
        current.legacyToCanonical[id]?.let { return it }
        if (id.startsWith("shelf:com.stasisstate.slatetv.plugin.jellyfin/") &&
            current.plugins.any { it.metadata.id == BuiltInLiveTvShelfPlugin.PLUGIN_ID }
        ) {
            return BuiltInLiveTvShelfPlugin.PLUGIN_ID
        }
        return id
    }
}

private fun discovery(
    context: Context,
    extensionRegistry: ExtensionRegistry,
): () -> List<ShelfPlugin> {
    val builtInLiveTv = BuiltInLiveTvShelfPlugin(context)
    val builtInAppChannels = BuiltInAppChannelsProvider(context)
    val installState = PluginInstallState.get(context)
    return {
        val appChannels = runCatching { builtInAppChannels.shelfPlugins() }.getOrDefault(emptyList())
        val jsonExtensions = runCatching { extensionRegistry.shelfPlugins() }.getOrDefault(emptyList())
        val liveTv = listOfNotNull(builtInLiveTv.takeIf { installState.isBuiltInInstalled(BuiltInLiveTvShelfPlugin.PLUGIN_ID) })
        liveTv + appChannels + jsonExtensions
    }
}
