package com.stasisstate.slatetv.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata

/**
 * Discovers external SHELF_PROVIDER plugin services.
 *
 * Security: Every external plugin is keyed canonically by its Android [ComponentName]
 * (`packageName/className`) so a third-party APK cannot spoof another plugin's manifest ID.
 */
internal class ShelfPluginRegistry(private val discover: () -> List<ShelfPlugin>) {
    constructor(context: Context) : this(discovery(context.applicationContext))

    @Volatile
    var plugins: List<ShelfPlugin> = emptyList()
        private set

    fun refresh(): List<ShelfPlugin> {
        plugins = discover().distinctBy { it.metadata.id }
        return plugins
    }
}

private fun discovery(context: Context): () -> List<ShelfPlugin> {
    val lock = Any()
    var cached = emptyMap<ComponentName, Pair<ShelfPluginMetadata, ShelfPlugin>>()
    return {
        val pm = context.packageManager
        val services = runCatching {
            pm.queryIntentServices(Intent(PluginContract.ACTION_SHELF_PROVIDER), PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        synchronized(lock) {
            cached = services.mapNotNull { result ->
                runCatching {
                    val service = result.serviceInfo ?: return@runCatching null
                    if (!service.exported || !service.enabled) return@runCatching null
                    val meta = service.metaData
                    if (meta?.getInt(PluginContract.META_API_VERSION, 1)?.let { it != PluginContract.API_VERSION } == true) {
                        return@runCatching null
                    }
                    val component = ComponentName(service.packageName, service.name)
                    // Key canonically by ComponentName to prevent cross-package ID spoofing
                    val canonicalId = "shelf:${component.flattenToShortString()}"
                    val declared = ShelfPluginMetadata(
                        id = canonicalId,
                        name = (meta?.getString(PluginContract.META_PLUGIN_NAME) ?: result.loadLabel(pm).toString()).take(120),
                        description = meta?.getString(PluginContract.META_PLUGIN_DESCRIPTION)?.take(160).orEmpty(),
                        version = PluginContract.API_VERSION.toString(),
                        packageName = service.packageName,
                        settingsActivityClassName = activityClass(meta?.getString(PluginContract.META_SETTINGS_ACTIVITY)),
                        guideActivityClassName = activityClass(meta?.getString(PluginContract.META_GUIDE_ACTIVITY)),
                    )
                    val previous = cached[component]?.takeIf { it.first == declared }
                    component to (previous ?: (declared to ExternalShelfPluginAdapter(context, component, declared)))
                }.getOrNull()
            }.sortedBy { it.first.flattenToString() }.toMap()
            cached.values.map { it.second }
        }
    }
}
