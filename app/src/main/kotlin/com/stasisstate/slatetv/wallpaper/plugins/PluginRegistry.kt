package com.stasisstate.slatetv.wallpaper.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.shelf.activityClass

/**
 * Unified registry of built-in plugins (Ambient Slate, Local Photos)
 * and dynamically discovered external AIDL APK plugins.
 *
 * External plugins are keyed canonically by [ComponentName] to prevent cross-package ID spoofing,
 * while maintaining seamless lookup compatibility for previously persisted manifest IDs.
 */
class PluginRegistry(
    private val context: Context?,
    val ambientPlugin: BackgroundPlugin = AmbientSlatePlugin(),
    private val localPlugin: BackgroundPlugin? = null,
) {
    private val builtInPlugins: List<BackgroundPlugin> = listOfNotNull(
        ambientPlugin,
        localPlugin,
    )

    private val lock = Any()

    @Volatile
    private var cachedExternal: List<BackgroundPlugin> = emptyList()

    @Volatile
    private var legacyAliases: Map<String, String> = emptyMap()

    fun refreshExternalPlugins(): List<BackgroundPlugin> {
        val ctx = context ?: return emptyList()
        val pm = ctx.packageManager
        val intent = Intent(PluginContract.ACTION_BACKGROUND_PROVIDER)
        val services = runCatching {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val aliases = mutableMapOf<String, String>()
        val discovered = services.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (!si.exported || !si.enabled) return@mapNotNull null
            val meta = si.metaData
            val apiVersion = meta?.getInt(PluginContract.META_API_VERSION, PluginContract.API_VERSION)
                ?: PluginContract.API_VERSION
            if (apiVersion != PluginContract.API_VERSION) return@mapNotNull null

            val component = ComponentName(si.packageName, si.name)
            // Key external plugins canonically by ComponentName (prevents ID spoofing)
            val canonicalId = "ext:${component.flattenToShortString()}"
            meta?.getString(PluginContract.META_PLUGIN_ID)?.takeIf { it.isNotBlank() }?.let { legacyId ->
                aliases.putIfAbsent(legacyId, canonicalId)
            }
            val name = (meta?.getString(PluginContract.META_PLUGIN_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: ri.loadLabel(pm)?.toString()
                ?: si.packageName).take(120)
            val description = (meta?.getString(PluginContract.META_PLUGIN_DESCRIPTION)
                ?.takeIf { it.isNotBlank() }
                ?: "External background plugin (${si.packageName})").take(160)
            val settingsActivity = activityClass(meta?.getString(PluginContract.META_SETTINGS_ACTIVITY))

            ExternalAidlPluginAdapter(
                context = ctx.applicationContext,
                serviceComponent = component,
                metadata = PluginMetadata(
                    id = canonicalId,
                    name = name,
                    description = description,
                    version = "1.0",
                    isExternal = true,
                    packageName = si.packageName,
                    settingsActivityClassName = settingsActivity,
                    supportsNext = true,
                ),
            )
        }.sortedBy { it.metadata.id }.distinctBy { it.metadata.id }

        synchronized(lock) {
            cachedExternal = discovered
            legacyAliases = aliases
        }
        return discovered
    }

    fun getAllPlugins(): List<BackgroundPlugin> = builtInPlugins + cachedExternal

    fun getPluginById(id: String): BackgroundPlugin {
        val all = getAllPlugins()
        all.firstOrNull { it.metadata.id == id }?.let { return it }
        val resolvedAlias = legacyAliases[id]
        if (resolvedAlias != null) {
            all.firstOrNull { it.metadata.id == resolvedAlias }?.let { return it }
        }
        return ambientPlugin
    }
}
