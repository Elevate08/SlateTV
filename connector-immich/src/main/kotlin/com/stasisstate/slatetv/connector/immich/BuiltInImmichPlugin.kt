package com.stasisstate.slatetv.connector.immich

import android.content.Context
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import java.io.File

/**
 * In-process Immich background provider for Slate TV.
 *
 * Images are downloaded by the connector's own [okhttp3.OkHttpClient] (origin-locked `x-api-key`, optional
 * TOFU-pinned certificate) into a bounded private cache and handed to the launcher as `file://` URIs with no
 * headers, so credentials and TLS trust decisions never leave this module.
 */
class BuiltInImmichPlugin(private val context: Context) : BackgroundPlugin {
    private val source: ImmichMediaSource by lazy {
        val app = context.applicationContext ?: context
        ImmichMediaSource(
            store = SharedPrefsImmichConfigStore(app),
            api = ImmichConnectorRuntime.api,
            cache = ImmichMediaFileCache(File(app.cacheDir, CACHE_DIR_NAME)),
        )
    }

    override val metadata: PluginMetadata = PluginMetadata(
        id = PLUGIN_ID,
        name = "Immich Photos",
        description = "Self-hosted photo library (direct sign-in, LAN discovery, albums & favorites)",
        version = "1.0.0",
        isExternal = false,
        packageName = context.packageName,
        settingsActivityClassName = ImmichSettingsActivity::class.java.name,
        supportsNext = true,
    )

    override suspend fun isConfigured(): Boolean = source.isConfigured()

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? = source.fetchNext(request)

    override suspend fun prefetch(request: PluginRequestContext, count: Int): List<BackgroundMedia> =
        source.prefetch(request, count)

    companion object {
        const val PLUGIN_ID = "immich"
        const val LEGACY_EXTERNAL_ID = "com.stasisstate.slatetv.plugin.immich"
        internal const val CACHE_DIR_NAME = "connector_immich_media"
    }
}
