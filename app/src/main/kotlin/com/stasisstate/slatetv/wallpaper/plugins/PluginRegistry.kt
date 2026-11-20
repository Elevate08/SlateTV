package com.stasisstate.slatetv.wallpaper.plugins

import android.content.Context
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin

/**
 * Unified registry of built-in background providers (Ambient Slate, Local Photos, Immich Photos)
 * and user-installed zero-code JSON background extensions.
 */
class PluginRegistry(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    val ambientPlugin: BackgroundPlugin = AmbientSlatePlugin(),
    private val localPlugin: BackgroundPlugin? = null,
    private val extraBuiltInPlugins: List<BackgroundPlugin> = emptyList(),
    private val jsonExtensionProvider: () -> List<BackgroundPlugin> = { emptyList() },
    private val legacyBuiltInAliases: Map<String, String> = emptyMap(),
    /** False for built-in plugins the user uninstalled from the plugin manager. */
    private val isBuiltInInstalled: (String) -> Boolean = { true },
) {
    private val builtInPlugins: List<BackgroundPlugin> = (
        listOfNotNull(ambientPlugin, localPlugin) + extraBuiltInPlugins
        ).distinctBy { it.metadata.id }

    @Volatile
    private var extensionPlugins: List<BackgroundPlugin> = emptyList()

    /** Refreshes the active list of enabled zero-code JSON background extensions. */
    fun refreshExtensions(): List<BackgroundPlugin> {
        val builtInIds = builtInPlugins.map { it.metadata.id }.toSet()
        val refreshed = runCatching { jsonExtensionProvider() }
            .getOrDefault(emptyList())
            .filter { it.metadata.id !in builtInIds }
            .distinctBy { it.metadata.id }
        extensionPlugins = refreshed
        return refreshed
    }

    fun getAllPlugins(): List<BackgroundPlugin> =
        builtInPlugins.filter { it === ambientPlugin || isBuiltInInstalled(it.metadata.id) } + extensionPlugins

    fun getPluginById(id: String): BackgroundPlugin {
        val all = getAllPlugins()
        all.firstOrNull { it.metadata.id == id }?.let { return it }
        legacyBuiltInAliases[id]?.let { mapped ->
            all.firstOrNull { it.metadata.id == mapped }?.let { return it }
        }
        if (id.startsWith("ext:")) {
            val legacyPkg = id.removePrefix("ext:").substringBefore("/")
            legacyBuiltInAliases[legacyPkg]?.let { mapped ->
                all.firstOrNull { it.metadata.id == mapped }?.let { return it }
            }
        }
        return ambientPlugin
    }
}
