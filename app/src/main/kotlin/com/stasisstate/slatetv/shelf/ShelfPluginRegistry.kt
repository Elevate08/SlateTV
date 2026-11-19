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

    /** Immutable snapshot published atomically so readers never observe plugins and aliases from different refreshes. */
    private class Snapshot(val plugins: List<ShelfPlugin>, val legacyToCanonical: Map<String, String>)

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyMap())

    val plugins: List<ShelfPlugin> get() = snapshot.plugins

    /** Legacy manifest IDs claimed by exactly one installed shelf service, mapped to its canonical ID. */
    val legacyAliases: Map<String, String> get() = snapshot.legacyToCanonical

    fun refresh(): List<ShelfPlugin> {
        val found = discover()
        val unique = found.distinctBy { it.metadata.id }
        snapshot = Snapshot(unique, unambiguousLegacyAliases(found))
        return unique
    }

    /** Resolves [id] (canonical or an unambiguous legacy manifest ID) to its canonical ID; unknown IDs pass through. */
    fun canonicalId(id: String): String = resolveCanonicalId(snapshot, id)

    /** Rewrites saved disabled IDs so unambiguous legacy manifest IDs become canonical IDs. */
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
        return current.legacyToCanonical[id] ?: id
    }
}

/** Implemented by shelf plugins that carry a legacy manifest ID (`PluginContract.META_PLUGIN_ID`). */
internal interface LegacyManifestIdClaimant {
    val legacyManifestId: String?
}

/**
 * Builds legacy-ID -> canonical-ID aliases, rejecting any legacy ID claimed by more than one
 * installed service (or by a plugin whose canonical ID differs from the claim target) so a second
 * APK cannot hijack a saved selection.
 */
internal fun unambiguousLegacyAliases(plugins: List<ShelfPlugin>): Map<String, String> {
    val canonicalIds = plugins.map { it.metadata.id }.toSet()
    val claims = plugins.mapNotNull { plugin ->
        val legacy = (plugin as? LegacyManifestIdClaimant)?.legacyManifestId?.takeIf { it.isNotBlank() }
        legacy?.let { it to plugin.metadata.id }
    }
    return claims.groupBy({ it.first }, { it.second })
        .filter { (legacy, owners) -> owners.toSet().size == 1 && legacy !in canonicalIds }
        .mapValues { it.value.first() }
}

private fun discovery(context: Context): () -> List<ShelfPlugin> {
    val lock = Any()
    var cached = emptyMap<ComponentName, Pair<ShelfPluginMetadata, ExternalShelfPluginAdapter>>()
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
                    val legacyId = meta?.getString(PluginContract.META_PLUGIN_ID)?.takeIf { it.isNotBlank() }
                    val previous = cached[component]?.takeIf { it.first == declared && it.second.legacyManifestId == legacyId }
                    component to (previous ?: (declared to ExternalShelfPluginAdapter(context, component, declared, legacyId)))
                }.getOrNull()
            }.sortedBy { it.first.flattenToString() }.toMap()
            cached.values.map { it.second }
        }
    }
}
