package com.stasisstate.slatetv.plugin.immich

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.ISlateBackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginInfo
import com.stasisstate.slatetv.plugin.api.PluginMediaItem
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

internal const val IMMICH_PACKAGE = "com.stasisstate.slatetv.plugin.immich"
private const val SLATE_PACKAGE = "com.stasisstate.slatetv"

internal object ImmichRuntime {
    val mutex = Mutex()
    val api = ImmichApiClient()
    private var plugin: ImmichBackgroundPlugin? = null

    // Access under mutex so saving and serving a batch use consistent credentials.
    fun plugin(store: ImmichConfigStore): ImmichBackgroundPlugin =
        plugin ?: ImmichBackgroundPlugin(store, api).also { plugin = it }
}

class ImmichBackgroundPluginService : Service() {
    private val store by lazy { SharedPrefsImmichConfigStore(this) }
    private val cache by lazy { ImmichMediaCache(this, ImmichRuntime.api) }

    private val binder = object : ISlateBackgroundPlugin.Stub() {
        override fun getApiVersion() = PluginContract.API_VERSION

        override fun getPluginInfo() = PluginInfo(
            id = IMMICH_PACKAGE,
            name = "Immich Photos",
            description = "Self-hosted Immich photo library (albums, favorites, random, recent)",
            version = "1.0.0",
            settingsActivityClassName = ImmichSettingsActivity::class.java.name,
        )

        override fun getNextMedia(options: Bundle?): PluginMediaItem? =
            load(1, options, single = true).firstOrNull()

        override fun getMediaBatch(count: Int, options: Bundle?): List<PluginMediaItem> =
            load(count, options, single = false)
    }

    override fun onBind(intent: Intent?) = binder

    private fun load(count: Int, options: Bundle?, single: Boolean): List<PluginMediaItem> {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        val limit = allowedMediaCount(count, packages)
        if (limit == 0) return emptyList()
        val request = PluginRequestContext(
            targetWidth = options?.getInt(PluginContract.OPT_TARGET_WIDTH, 1920) ?: 1920,
            targetHeight = options?.getInt(PluginContract.OPT_TARGET_HEIGHT, 1080) ?: 1080,
            preferLandscape = options?.getBoolean(PluginContract.OPT_PREFER_LANDSCAPE, true) ?: true,
            lastMediaId = options?.getString(PluginContract.OPT_LAST_MEDIA_ID),
        )
        return runBlocking(Dispatchers.IO) {
            ImmichRuntime.mutex.withLock {
                val config = store.load()
                val plugin = ImmichRuntime.plugin(store)
                val media = if (single) listOfNotNull(plugin.fetchNext(request)) else plugin.prefetch(request, limit)
                media.mapNotNull { item ->
                    item.forExternalHost {
                        try {
                            cache.publish(config, item, packages).toString()
                        } catch (_: IOException) {
                            null
                        }
                    }?.let(PluginMediaItem::from)
                }
            }
        }
    }
}

internal fun allowedMediaCount(count: Int, packages: Array<out String>): Int =
    if (SLATE_PACKAGE in packages) count.coerceIn(0, 5) else 0

internal fun BackgroundMedia.forExternalHost(publish: () -> String?): BackgroundMedia? {
    if (!allowUntrustedTls) return this
    val uri = publish() ?: return null
    return copy(imageUri = uri, headers = emptyMap(), allowUntrustedTls = false)
}
