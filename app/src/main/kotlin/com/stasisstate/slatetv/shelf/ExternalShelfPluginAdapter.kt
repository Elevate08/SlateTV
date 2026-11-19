package com.stasisstate.slatetv.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.stasisstate.slatetv.plugin.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ExternalShelfPluginAdapter(
    private val context: Context,
    private val component: ComponentName,
    private val declared: ShelfPluginMetadata,
) : ShelfPlugin {
    @Volatile
    override var metadata = declared
        private set

    private var consecutiveTimeouts = 0
    private var cooldownUntil = 0L

    override suspend fun fetchShelf(): ShelfData? {
        if (SystemClock.elapsedRealtime() < cooldownUntil) return null

        val connected = CompletableDeferred<ISlateShelfPlugin?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(ISlateShelfPlugin.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) { connected.complete(null) }
            override fun onBindingDied(name: ComponentName) { connected.complete(null) }
            override fun onNullBinding(name: ComponentName) { connected.complete(null) }
        }
        val intent = Intent(PluginContract.ACTION_SHELF_PROVIDER).setComponent(component)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            return null
        }
        try {
            val service = withTimeoutOrNull(4_000) { Link(connected.await()) }
                ?: return recordTimeout()
            val remote = service.remote ?: return null
            val response = CompletableDeferred<Pair<ShelfPluginMetadata, ShelfData?>?>()
            val future = runCatching {
                executor.submit {
                    response.complete(runCatching {
                        if (remote.apiVersion != PluginContract.API_VERSION) return@runCatching null
                        val info = remote.shelfInfo
                        val resolved = info?.let {
                            declared.copy(
                                name = it.name.take(120).ifBlank { declared.name },
                                description = it.description.take(160),
                                version = it.version.take(32),
                                settingsActivityClassName = activityClass(it.settingsActivityClassName)
                                    ?: declared.settingsActivityClassName,
                                guideActivityClassName = activityClass(it.guideActivityClassName)
                                    ?: declared.guideActivityClassName,
                            )
                        } ?: declared
                        val payload = remote.getShelfPayload(Bundle())?.let { sanitizePayload(it, resolved) }
                        resolved to payload
                    }.getOrNull())
                }
            }.getOrNull() ?: return null
            try {
                val result = withTimeoutOrNull(4_000) { RpcResult(response.await()) }
                    ?: return recordTimeout()
                consecutiveTimeouts = 0
                val (resolved, payload) = result.value ?: return null
                metadata = resolved.copy(
                    guideActivityClassName = payload?.guideActivityClassName ?: resolved.guideActivityClassName,
                )
                return payload
            } finally {
                future.cancel(true)
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun recordTimeout(): ShelfData? {
        if (++consecutiveTimeouts >= 2) {
            cooldownUntil = SystemClock.elapsedRealtime() + 30_000
            consecutiveTimeouts = 0
        }
        return null
    }

    private data class Link(val remote: ISlateShelfPlugin?)
    private data class RpcResult(val value: Pair<ShelfPluginMetadata, ShelfData?>?)

    companion object {
        // Binder calls may ignore interruption. Bound the number of stranded threads.
        private val executor = ThreadPoolExecutor(
            0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ) { work -> Thread(work, "slate-shelf-rpc").apply { isDaemon = true } }
    }
}
