package com.stasisstate.slatetv.wallpaper.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.ISlateBackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Crash-isolated, timeout-enforced AIDL client for external APK plugins.
 *
 * Security & stability boundaries:
 * - Enforces a real deadline on BOTH binding AND the synchronous Binder RPC transaction
 *   using a dedicated daemon thread pool so a stalled plugin can never block `Dispatchers.IO`.
 * - Circuit-breaks a repeatedly stalling plugin for a cooldown window.
 * - Namespaces returned IDs, bounds batch/field lengths, blocks `file://` URIs from external APKs,
 *   and ignores untrusted TLS override requests from external plugins (host controls TLS policy).
 */
class ExternalAidlPluginAdapter(
    private val context: Context,
    private val serviceComponent: ComponentName,
    override val metadata: PluginMetadata,
) : BackgroundPlugin {

    private val consecutiveTimeouts = AtomicInteger(0)
    private val cooldownUntilMs = AtomicLong(0L)

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? {
        return withBoundService { service ->
            val options = buildOptions(request)
            service.getNextMedia(options)?.toBackgroundMedia()?.let(::sanitizeExternalMedia)
        }
    }

    override suspend fun prefetch(request: PluginRequestContext, count: Int): List<BackgroundMedia> {
        return withBoundService { service ->
            val options = buildOptions(request)
            service.getMediaBatch(count.coerceIn(1, MAX_BATCH_SIZE), options)
                ?.take(MAX_BATCH_SIZE)
                ?.mapNotNull { sanitizeExternalMedia(it.toBackgroundMedia()) }
                .orEmpty()
        } ?: emptyList()
    }

    private fun sanitizeExternalMedia(raw: BackgroundMedia): BackgroundMedia? {
        val uri = raw.imageUri.trim()
        if (uri.isBlank() || uri.length > MAX_URI_LENGTH) return null
        // Disallow external plugins from reading arbitrary local file:// paths inside launcher process
        val allowedScheme = uri.startsWith("https://") ||
            uri.startsWith("http://") ||
            uri.startsWith("ambient://") ||
            uri.startsWith("content://")
        if (!allowedScheme) return null

        val safeHeaders = raw.headers.entries
            .take(MAX_HEADERS)
            .associate { it.key.take(128) to it.value.take(2048) }

        return raw.copy(
            id = "${metadata.id}:${raw.id.take(128)}",
            imageUri = uri,
            headers = safeHeaders,
            title = raw.title?.take(120),
            subtitle = raw.subtitle?.take(160),
            allowUntrustedTls = false, // Host strictly controls TLS trust policy
        )
    }

    private fun buildOptions(request: PluginRequestContext): Bundle = Bundle().apply {
        putInt(PluginContract.OPT_TARGET_WIDTH, request.targetWidth)
        putInt(PluginContract.OPT_TARGET_HEIGHT, request.targetHeight)
        putBoolean(PluginContract.OPT_PREFER_LANDSCAPE, request.preferLandscape)
        request.lastMediaId?.removePrefix("${metadata.id}:")?.let {
            putString(PluginContract.OPT_LAST_MEDIA_ID, it)
        }
    }

    private suspend fun <T> withBoundService(block: (ISlateBackgroundPlugin) -> T?): T? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (now < cooldownUntilMs.get()) return@withContext null

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
                    ?: run {
                        recordTimeout()
                        return@withContext null
                    }

                val future = rpcExecutor.submit<T?> {
                    if (service.apiVersion == PluginContract.API_VERSION) {
                        block(service)
                    } else {
                        null
                    }
                }
                try {
                    val result = future.get(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    consecutiveTimeouts.set(0)
                    result
                } catch (_: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    recordTimeout()
                    null
                } catch (e: CancellationException) {
                    future.cancel(true)
                    throw e
                } catch (_: Exception) {
                    null
                }
            } finally {
                runCatching { context.unbindService(connection) }
            }
        }

    private fun recordTimeout() {
        val count = consecutiveTimeouts.incrementAndGet()
        if (count >= 2) {
            cooldownUntilMs.set(System.currentTimeMillis() + STALL_COOLDOWN_MS)
        }
    }

    companion object {
        private const val BIND_TIMEOUT_MS = 4_000L
        private const val RPC_TIMEOUT_MS = 4_000L
        private const val STALL_COOLDOWN_MS = 30_000L
        private const val MAX_BATCH_SIZE = 8
        private const val MAX_HEADERS = 16
        private const val MAX_URI_LENGTH = 2048

        private val rpcExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "slate-aidl-rpc").apply { isDaemon = true }
        }
    }
}
