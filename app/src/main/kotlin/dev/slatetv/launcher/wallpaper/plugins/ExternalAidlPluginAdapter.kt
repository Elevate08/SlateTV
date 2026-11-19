package dev.slatetv.launcher.wallpaper.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.BackgroundPlugin
import dev.slatetv.plugin.api.ISlateBackgroundPlugin
import dev.slatetv.plugin.api.PluginContract
import dev.slatetv.plugin.api.PluginMetadata
import dev.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Crash-isolated, timeout-guarded AIDL client for external APK plugins.
 *
 * Binds only for the duration of a fetch call and immediately unbinds so
 * external plugins never linger in Shield RAM or slow down idle home rendering.
 */
class ExternalAidlPluginAdapter(
    private val context: Context,
    private val serviceComponent: ComponentName,
    override val metadata: PluginMetadata,
) : BackgroundPlugin {

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? {
        return withBoundService { service ->
            val options = buildOptions(request)
            service.getNextMedia(options)?.toBackgroundMedia()
        }
    }

    override suspend fun prefetch(request: PluginRequestContext, count: Int): List<BackgroundMedia> {
        return withBoundService { service ->
            val options = buildOptions(request)
            service.getMediaBatch(count.coerceIn(1, 10), options)
                ?.map { it.toBackgroundMedia() }
                .orEmpty()
        } ?: emptyList()
    }

    private fun buildOptions(request: PluginRequestContext): Bundle = Bundle().apply {
        putInt(PluginContract.OPT_TARGET_WIDTH, request.targetWidth)
        putInt(PluginContract.OPT_TARGET_HEIGHT, request.targetHeight)
        putBoolean(PluginContract.OPT_PREFER_LANDSCAPE, request.preferLandscape)
        request.lastMediaId?.let { putString(PluginContract.OPT_LAST_MEDIA_ID, it) }
    }

    private suspend fun <T> withBoundService(block: (ISlateBackgroundPlugin) -> T?): T? =
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<ISlateBackgroundPlugin?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    deferred.complete(ISlateBackgroundPlugin.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }

            val intent = Intent(PluginContract.ACTION_BACKGROUND_PROVIDER).apply {
                component = serviceComponent
            }

            val bound = runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)

            if (!bound) return@withContext null

            try {
                val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                    ?: return@withContext null
                runCatching {
                    if (service.apiVersion == PluginContract.API_VERSION) {
                        block(service)
                    } else {
                        null
                    }
                }.getOrNull()
            } finally {
                runCatching { context.unbindService(connection) }
            }
        }

    companion object {
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}
