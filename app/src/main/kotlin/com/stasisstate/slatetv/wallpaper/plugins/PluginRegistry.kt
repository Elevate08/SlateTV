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

    /** Immutable external-plugin state, published as one unit so readers never see torn plugins/aliases. */
    internal data class ExternalSnapshot(
        val plugins: List<BackgroundPlugin>,
        val legacyIdAliases: Map<String, String>,
    )

    @Volatile
    private var snapshot = ExternalSnapshot(emptyList(), emptyMap())

    internal val externalSnapshot: ExternalSnapshot get() = snapshot

    fun refreshExternalPlugins(): List<BackgroundPlugin> {
        val ctx = context ?: return emptyList()
        val pm = ctx.packageManager
        val intent = Intent(PluginContract.ACTION_BACKGROUND_PROVIDER)
        val services = runCatching {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val legacyClaims = mutableListOf<Pair<String, String>>()
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
                legacyClaims += legacyId to canonicalId
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

        return publishExternal(discovered, legacyClaims)
    }

    /**
     * Atomically installs [discovered] plugins plus legacy-ID aliases. [legacyClaims] are
     * (manifest ID, canonical ID) pairs from every discovered service; a manifest ID claimed by
     * more than one distinct service is ambiguous and is rejected entirely.
     */
    internal fun publishExternal(
        discovered: List<BackgroundPlugin>,
        legacyClaims: List<Pair<String, String>>,
    ): List<BackgroundPlugin> {
        val builtInIds = builtInPlugins.map { it.metadata.id }.toSet()
        val canonicalIds = discovered.map { it.metadata.id }.toSet()
        val aliases = legacyClaims.groupBy({ it.first }, { it.second })
            .filter { (legacy, owners) ->
                owners.toSet().size == 1 && legacy !in builtInIds && legacy !in canonicalIds
            }
            .mapValues { it.value.first() }
        snapshot = ExternalSnapshot(discovered, aliases)
        return discovered
    }

    fun getAllPlugins(): List<BackgroundPlugin> = builtInPlugins + snapshot.plugins

    fun getPluginById(id: String): BackgroundPlugin {
        val current = snapshot
        val all = builtInPlugins + current.plugins
        all.firstOrNull { it.metadata.id == id }?.let { return it }
        val resolvedAlias = current.legacyIdAliases[id]
        if (resolvedAlias != null) {
            all.firstOrNull { it.metadata.id == resolvedAlias }?.let { return it }
        }
        return ambientPlugin
    }
}
