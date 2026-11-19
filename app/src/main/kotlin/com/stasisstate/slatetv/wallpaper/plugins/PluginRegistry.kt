package com.stasisstate.slatetv.wallpaper.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.immich.ImmichBackgroundPlugin

/**
 * Unified registry of built-in plugins (Immich, Ambient Slate, Local Photos)
 * and dynamically discovered external AIDL APK plugins.
 */
class PluginRegistry(
    private val context: Context?,
    val immichPlugin: BackgroundPlugin,
    val ambientPlugin: BackgroundPlugin = AmbientSlatePlugin(),
    private val localPlugin: BackgroundPlugin? = null,
) {
    private val builtInPlugins: List<BackgroundPlugin> = listOfNotNull(
        immichPlugin,
        ambientPlugin,
        localPlugin,
    )

    private var cachedExternal: List<BackgroundPlugin> = emptyList()

    fun refreshExternalPlugins(): List<BackgroundPlugin> {
        val ctx = context ?: return emptyList()
        val pm = ctx.packageManager
        val intent = Intent(PluginContract.ACTION_BACKGROUND_PROVIDER)
        val services = runCatching {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val discovered = services.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (!si.exported) return@mapNotNull null
            val meta = si.metaData
            val apiVersion = meta?.getInt(PluginContract.META_API_VERSION, PluginContract.API_VERSION)
                ?: PluginContract.API_VERSION
            if (apiVersion != PluginContract.API_VERSION) return@mapNotNull null

            val component = ComponentName(si.packageName, si.name)
            val id = meta?.getString(PluginContract.META_PLUGIN_ID)
                ?.takeIf { it.isNotBlank() }
                ?: "external.${si.packageName}.${si.name}"
            val name = meta?.getString(PluginContract.META_PLUGIN_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: ri.loadLabel(pm)?.toString()
                ?: si.packageName
            val description = meta?.getString(PluginContract.META_PLUGIN_DESCRIPTION)
                ?.takeIf { it.isNotBlank() }
                ?: "External background plugin (${si.packageName})"
            val settingsActivity = meta?.getString(PluginContract.META_SETTINGS_ACTIVITY)
                ?.takeIf { it.isNotBlank() }

            ExternalAidlPluginAdapter(
                context = ctx.applicationContext,
                serviceComponent = component,
                metadata = PluginMetadata(
                    id = id,
                    name = name,
                    description = description,
                    version = "1.0",
                    isExternal = true,
                    packageName = si.packageName,
                    settingsActivityClassName = settingsActivity,
                    supportsNext = true,
                ),
            )
        }
        cachedExternal = discovered
        return discovered
    }

    fun getAllPlugins(): List<BackgroundPlugin> = builtInPlugins + cachedExternal

    fun getPluginById(id: String): BackgroundPlugin {
        return getAllPlugins().firstOrNull { it.metadata.id == id } ?: ambientPlugin
    }

    fun isImmichPlugin(id: String): Boolean = id == ImmichBackgroundPlugin.PLUGIN_ID
}
