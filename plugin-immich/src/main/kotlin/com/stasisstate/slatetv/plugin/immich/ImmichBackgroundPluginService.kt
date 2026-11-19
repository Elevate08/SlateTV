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
import kotlinx.coroutines.CoroutineStart
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
 * - Cold misses wait at most [rpcWaitBudgetMs] (2.8s, leaving 1.2s headroom) for the first ready item while background
 *   refill continues warming the remaining batch for the host's next retry.
 */
internal class ImmichMediaBuffer(
    private val api: ImmichApiClient,
    private val refillScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val rpcWaitBudgetMs: Long = RPC_WAIT_BUDGET_MS,
    private val handoffGraceMs: Long = HANDOFF_GRACE_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Test seam: replaces [ImmichBackgroundPlugin.prefetch] when non-null. */
    private val prefetchOverride: (suspend (PluginRequestContext, Int) -> List<BackgroundMedia>)? = null,
) {
    private val stateLock = Any()
    private val readyQueue = ArrayDeque<PluginMediaItem>()
    private var configGeneration = 0L
    private var activeRefill: Job? = null
    private var itemSignal: CompletableDeferred<Unit>? = null
    private var plugin: ImmichBackgroundPlugin? = null

    /** Cached media names handed to Slate TV, with handoff time, kept alive for [handoffGraceMs]. */
    private val recentHandoffs = ArrayDeque<Pair<String, Long>>()

    /** Test seam: invoked while holding [stateLock] right before a waiting `take` subscribes to [itemSignal]. */
    @Volatile
    internal var beforeSubscribeLocked: (() -> Unit)? = null

    /**
     * Cache file names that must not be evicted: every `content://` item still queued plus items
     * handed to Slate TV within the grace window (the host may not have opened them yet).
     */
    fun protectedMediaNames(): Set<String> = synchronized(stateLock) {
        pruneHandoffsLocked()
        buildSet {
            readyQueue.forEach { item -> immichMediaNameOf(item.imageUri)?.let(::add) }
            recentHandoffs.forEach { (name, _) -> add(name) }
        }
    }

    private fun pruneHandoffsLocked() {
        val cutoff = nowMs() - handoffGraceMs
        while (recentHandoffs.isNotEmpty() &&
            (recentHandoffs.first().second < cutoff || recentHandoffs.size > MAX_GRACE_ITEMS)
        ) {
            recentHandoffs.removeFirst()
        }
    }

    private fun recordHandoffsLocked(items: List<PluginMediaItem>) {
        if (items.isEmpty()) return
        val now = nowMs()
        items.forEach { item -> immichMediaNameOf(item.imageUri)?.let { recentHandoffs.addLast(it to now) } }
        pruneHandoffsLocked()
    }

    fun invalidateAll() {
        synchronized(stateLock) {
            configGeneration++
            activeRefill?.cancel()
            activeRefill = null
            readyQueue.clear() // also drops queued-item cache protection
            pruneHandoffsLocked()
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
        publishLocal: suspend (ImmichConfig, BackgroundMedia, shouldPublish: () -> Boolean) -> String?,
    ): List<PluginMediaItem> {
        if (limit <= 0) return emptyList()

        return withTimeoutOrNull<List<PluginMediaItem>>(rpcWaitBudgetMs) {
            var mayStartRefill = true
            while (true) {
                // Pop, refill check/start and signal subscription happen under ONE lock acquisition so a
                // refill cannot enqueue an item and clear [itemSignal] between our check and subscribe.
                val signal = synchronized(stateLock) {
                    var ready = popReadyLocked(limit, request.lastMediaId)
                    if (ready.isNotEmpty()) {
                        recordHandoffsLocked(ready)
                    }
                    // Top up whenever we return items; on an empty queue only the first pass may start a
                    // refill, so a refill that yields nothing returns empty instead of spinning new refills
                    // until the RPC budget expires.
                    if ((mayStartRefill || ready.isNotEmpty()) && readyQueue.size < LOW_WATERMARK) {
                        ensureRefillLocked(store, request, publishLocal)
                        // A refill dispatched inline (or one that finished instantly) may already have
                        // enqueued items while we held the (reentrant) lock.
                        if (ready.isEmpty()) {
                            ready = popReadyLocked(limit, request.lastMediaId)
                            if (ready.isNotEmpty()) recordHandoffsLocked(ready)
                        }
                    }
                    mayStartRefill = false
                    if (ready.isNotEmpty()) return@withTimeoutOrNull ready
                    if (activeRefill?.isActive != true) return@withTimeoutOrNull emptyList()
                    beforeSubscribeLocked?.invoke()
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
        publishLocal: suspend (ImmichConfig, BackgroundMedia, shouldPublish: () -> Boolean) -> String?,
    ) {
        if (activeRefill?.isActive == true) return
        val gen = configGeneration
        val activePlugin = plugin ?: ImmichBackgroundPlugin(store, api).also { plugin = it }
        lateinit var launchedJob: Job
        // LAZY so [activeRefill] is assigned before the body (and its finally block) can run, keeping the
        // `activeRefill === launchedJob` identity check race-free even when the refill completes instantly.
        launchedJob = refillScope.launch(start = CoroutineStart.LAZY) {
            try {
                val config = store.load()
                if (!config.isConfigured) return@launch
                val candidates = prefetchOverride?.invoke(request, TARGET_READY_COUNT)
                    ?: activePlugin.prefetch(request, TARGET_READY_COUNT)
                for (item in candidates) {
                    if (!isActive) break
                    val converted = item.forExternalHost {
                        try {
                            // Re-checked under the cache lock so an invalidated refill never evicts,
                            // publishes, or grants URI permissions.
                            publishLocal(config, item) { synchronized(stateLock) { configGeneration == gen } }
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
        launchedJob.start()
    }

    companion object {
        private const val RPC_WAIT_BUDGET_MS = 2_800L
        private const val LOW_WATERMARK = 3
        private const val TARGET_READY_COUNT = 4
        private const val MAX_READY_ITEMS = 8
        private const val HANDOFF_GRACE_MS = 30_000L
        private const val MAX_GRACE_ITEMS = 12
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
                publishLocal = { config, item, shouldPublish ->
                    cache.publish(
                        config = config,
                        media = item,
                        grantPackages = listOf(SLATE_PACKAGE),
                        protectedNames = { ImmichRuntime.buffer.protectedMediaNames() },
                        shouldPublish = shouldPublish,
                    )?.toString()
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

/** Inline so a suspending [publish] (and its cancellation) runs directly in the caller's coroutine. */
internal inline fun BackgroundMedia.forExternalHost(publish: () -> String?): BackgroundMedia? {
    if (!allowUntrustedTls) return this
    val uri = publish() ?: return null
    return copy(imageUri = uri, headers = emptyMap(), allowUntrustedTls = false)
}
