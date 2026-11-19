package com.stasisstate.slatetv.plugin.immich

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.ISlateBackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginContract
import com.stasisstate.slatetv.plugin.api.PluginInfo
import com.stasisstate.slatetv.plugin.api.PluginMediaItem
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest

internal const val IMMICH_PACKAGE = "com.stasisstate.slatetv.plugin.immich"
private const val SLATE_PACKAGE = "com.stasisstate.slatetv"

internal object ImmichRuntime {
    val api = ImmichApiClient()
    val buffer = ImmichMediaBuffer(api)

    fun invalidateAll() {
        buffer.invalidateAll()
    }
}

/**
 * Non-blocking background prefetch buffer for [ImmichBackgroundPluginService].
 *
 * Ensures Binder RPCs (`getNextMedia` / `getMediaBatch`) never block past Slate TV's 4s deadline
 * during Immich album searches or self-signed TLS image caching:
 * - Warm hits return immediately from [readyQueue] and trigger async refill in the background.
 * - Cold misses wait at most [rpcWaitBudgetMs] (3.2s) for the first ready item while background
 *   refill continues warming the remaining batch for the host's next retry.
 */
internal class ImmichMediaBuffer(
    private val api: ImmichApiClient,
    private val refillScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val rpcWaitBudgetMs: Long = RPC_WAIT_BUDGET_MS,
) {
    private val stateLock = Any()
    private val readyQueue = ArrayDeque<PluginMediaItem>()
    private var configGeneration = 0L
    private var activeRefill: Job? = null
    private var itemSignal: CompletableDeferred<Unit>? = null
    private var plugin: ImmichBackgroundPlugin? = null

    fun invalidateAll() {
        synchronized(stateLock) {
            configGeneration++
            activeRefill?.cancel()
            activeRefill = null
            readyQueue.clear()
            plugin = null
            api.resetPagination()
            itemSignal?.complete(Unit)
            itemSignal = null
        }
    }

    suspend fun take(
        limit: Int,
        request: PluginRequestContext,
        store: ImmichConfigStore,
        publishLocal: (ImmichConfig, BackgroundMedia) -> String?,
    ): List<PluginMediaItem> {
        if (limit <= 0) return emptyList()

        val immediate = synchronized(stateLock) {
            val popped = popReadyLocked(limit, request.lastMediaId)
            if (readyQueue.size < LOW_WATERMARK) {
                ensureRefillLocked(store, request, publishLocal)
            }
            popped
        }
        if (immediate.isNotEmpty()) return immediate

        return withTimeoutOrNull<List<PluginMediaItem>>(rpcWaitBudgetMs) {
            while (true) {
                val ready = synchronized(stateLock) { popReadyLocked(limit, request.lastMediaId) }
                if (ready.isNotEmpty()) return@withTimeoutOrNull ready

                val signal = synchronized(stateLock) {
                    if (activeRefill?.isActive != true) return@withTimeoutOrNull emptyList()
                    itemSignal ?: CompletableDeferred<Unit>().also { itemSignal = it }
                }
                signal.await()
            }
            emptyList()
        }.orEmpty()
    }

    private fun popReadyLocked(limit: Int, lastMediaId: String?): List<PluginMediaItem> {
        if (readyQueue.isEmpty()) return emptyList()
        val result = ArrayList<PluginMediaItem>(minOf(limit, readyQueue.size))
        var skippedOnce = false
        while (result.size < limit && readyQueue.isNotEmpty()) {
            val candidate = readyQueue.removeFirst()
            if (!skippedOnce && lastMediaId != null && candidate.id == lastMediaId && readyQueue.isNotEmpty()) {
                readyQueue.addLast(candidate)
                skippedOnce = true
                continue
            }
            result += candidate
        }
        return result
    }

    private fun ensureRefillLocked(
        store: ImmichConfigStore,
        request: PluginRequestContext,
        publishLocal: (ImmichConfig, BackgroundMedia) -> String?,
    ) {
        if (activeRefill?.isActive == true) return
        val gen = configGeneration
        val activePlugin = plugin ?: ImmichBackgroundPlugin(store, api).also { plugin = it }
        var launchedJob: Job? = null
        launchedJob = refillScope.launch {
            try {
                val config = store.load()
                if (!config.isConfigured) return@launch
                val candidates = activePlugin.prefetch(request, TARGET_READY_COUNT)
                for (item in candidates) {
                    if (!isActive) break
                    val converted = item.forExternalHost {
                        try {
                            publishLocal(config, item)
                        } catch (_: IOException) {
                            null
                        }
                    }?.let(PluginMediaItem::from) ?: continue

                    synchronized(stateLock) {
                        if (configGeneration != gen) return@launch
                        if (readyQueue.size < MAX_READY_ITEMS) {
                            readyQueue.addLast(converted)
                        }
                        itemSignal?.complete(Unit)
                        itemSignal = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep plugin resilient; host retries on backoff
            } finally {
                synchronized(stateLock) {
                    if (activeRefill === launchedJob) {
                        activeRefill = null
                    }
                    itemSignal?.complete(Unit)
                    itemSignal = null
                }
            }
        }
        activeRefill = launchedJob
    }

    companion object {
        private const val RPC_WAIT_BUDGET_MS = 3_200L
        private const val LOW_WATERMARK = 3
        private const val TARGET_READY_COUNT = 4
        private const val MAX_READY_ITEMS = 8
    }
}

class ImmichBackgroundPluginService : Service() {
    private val store by lazy { SharedPrefsImmichConfigStore(applicationContext) }
    private val cache by lazy { ImmichMediaCache(applicationContext, ImmichRuntime.api) }

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
            load(1, options).firstOrNull()

        override fun getMediaBatch(count: Int, options: Bundle?): List<PluginMediaItem> =
            load(count, options)
    }

    override fun onBind(intent: Intent?) = binder

    private fun load(count: Int, options: Bundle?): List<PluginMediaItem> {
        val callingUid = Binder.getCallingUid()
        if (!isCallerAuthorized(callingUid)) return emptyList()
        val limit = count.coerceIn(0, 5)
        if (limit == 0) return emptyList()

        val request = PluginRequestContext(
            targetWidth = options?.getInt(PluginContract.OPT_TARGET_WIDTH, 1920) ?: 1920,
            targetHeight = options?.getInt(PluginContract.OPT_TARGET_HEIGHT, 1080) ?: 1080,
            preferLandscape = options?.getBoolean(PluginContract.OPT_PREFER_LANDSCAPE, true) ?: true,
            lastMediaId = options?.getString(PluginContract.OPT_LAST_MEDIA_ID),
        )
        return runBlocking {
            ImmichRuntime.buffer.take(
                limit = limit,
                request = request,
                store = store,
                publishLocal = { config, item ->
                    cache.publish(config, item, listOf(SLATE_PACKAGE)).toString()
                },
            )
        }
    }

    private fun isCallerAuthorized(callingUid: Int): Boolean {
        val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
        if (SLATE_PACKAGE !in packages) return false
        val pinned = BuildConfig.TRUSTED_SLATE_CERT_SHA256
        val sigMatches = packageManager.checkSignatures(packageName, SLATE_PACKAGE) == PackageManager.SIGNATURE_MATCH
        val certs = if (pinned.isNotBlank()) signingCertSha256s(packageManager, SLATE_PACKAGE) else emptySet()
        return isTrustedCaller(
            packages = packages,
            signatureMatchesSelf = sigMatches,
            pinnedCertSha256 = pinned,
            callerCertSha256s = certs,
        )
    }
}

internal fun allowedMediaCount(count: Int, packages: Array<out String>): Int =
    if (SLATE_PACKAGE in packages) count.coerceIn(0, 5) else 0

internal fun isTrustedCaller(
    packages: Array<out String>,
    signatureMatchesSelf: Boolean,
    pinnedCertSha256: String = "",
    callerCertSha256s: Set<String> = emptySet(),
): Boolean {
    if (SLATE_PACKAGE !in packages) return false
    val expected = pinnedCertSha256.trim().lowercase()
    return if (expected.isNotEmpty()) {
        expected in callerCertSha256s
    } else {
        signatureMatchesSelf
    }
}

private fun signingCertSha256s(pm: PackageManager, targetPackage: String): Set<String> = runCatching {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(targetPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = info.signingInfo ?: return@runCatching emptySet()
        if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(targetPackage, PackageManager.GET_SIGNATURES).signatures
    }
    val md = MessageDigest.getInstance("SHA-256")
    signatures.orEmpty().map { sig ->
        md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()
}.getOrDefault(emptySet())

internal fun BackgroundMedia.forExternalHost(publish: () -> String?): BackgroundMedia? {
    if (!allowUntrustedTls) return this
    val uri = publish() ?: return null
    return copy(imageUri = uri, headers = emptyMap(), allowUntrustedTls = false)
}
