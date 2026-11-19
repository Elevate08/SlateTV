package com.stasisstate.slatetv.wallpaper

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs blocking open-and-consume work for a [Closeable] resource on a bounded executor with:
 * - a per-key concurrency cap (a wedged key holds at most [maxPerKey] workers; further requests
 *   for that key fail fast instead of stranding more threads),
 * - a timeout that fires [onCancel] (e.g. `CancellationSignal.cancel()`) and closes the active resource,
 * - a late-arrival guard so a resource returned after timeout/cancellation is always closed.
 *
 * Pure JVM so it can be unit tested without Android framework classes.
 */
internal class BoundedResourceRunner(
    private val executor: ExecutorService,
    private val maxPerKey: Int = DEFAULT_MAX_PER_KEY,
) {
    private val permits = ConcurrentHashMap<String, Semaphore>()

    suspend fun <R : Closeable, T> run(
        key: String,
        timeoutMs: Long,
        onCancel: () -> Unit,
        open: () -> R?,
        consume: (R) -> T?,
    ): T? {
        val semaphore = permits.computeIfAbsent(key) { Semaphore(maxPerKey) }
        if (!semaphore.tryAcquire()) return null

        val result = CompletableDeferred<T?>()
        val cancelled = AtomicBoolean(false)
        val activeResource = AtomicReference<Closeable?>(null)

        val future: Future<*> = try {
            executor.submit(Runnable {
                try {
                    if (cancelled.get()) return@Runnable
                    val resource = open() ?: return@Runnable
                    activeResource.set(resource)
                    if (cancelled.get()) {
                        activeResource.getAndSet(null).closeQuietly()
                        return@Runnable
                    }
                    try {
                        result.complete(consume(resource))
                    } finally {
                        activeResource.getAndSet(null).closeQuietly()
                    }
                } catch (_: Throwable) {
                    // Treated as a failed load.
                } finally {
                    result.complete(null)
                    semaphore.release()
                }
            })
        } catch (_: RejectedExecutionException) {
            semaphore.release()
            return null
        }

        return try {
            withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            if (!result.isCompleted) {
                cancelled.set(true)
                runCatching(onCancel)
                activeResource.getAndSet(null).closeQuietly()
                future.cancel(true)
            }
        }
    }

    private fun Closeable?.closeQuietly() {
        if (this == null) return
        runCatching { close() }
    }

    companion object {
        const val DEFAULT_MAX_PER_KEY = 2
    }
}

/**
 * Cancellation-aware, per-authority-bounded reader for `content://` URIs served by plugin providers.
 * Uses `openAssetFileDescriptor(uri, "r", CancellationSignal)` so a timeout actually cancels the
 * Binder call, and caps concurrent opens per authority so one stalled provider cannot starve others.
 */
internal class ContentUriOpener(
    threadName: String,
    maxThreads: Int = 4,
    maxPerAuthority: Int = BoundedResourceRunner.DEFAULT_MAX_PER_KEY,
) {
    private val executor = ThreadPoolExecutor(
        0,
        maxThreads,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { work -> Thread(work, threadName).apply { isDaemon = true } }

    private val runner = BoundedResourceRunner(executor, maxPerAuthority)

    suspend fun <T> read(
        context: Context,
        uri: String,
        timeoutMs: Long,
        maxBytes: Long,
        consume: (InputStream) -> T?,
    ): T? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val resolver = context.contentResolver
        val signal = CancellationSignal()
        return runner.run(
            key = parsed.authority.orEmpty(),
            timeoutMs = timeoutMs,
            onCancel = { signal.cancel() },
            open = { resolver.openAssetFileDescriptor(parsed, "r", signal) },
            consume = { afd ->
                val declared = afd.length
                if (declared != AssetFileDescriptor.UNKNOWN_LENGTH && declared > maxBytes) {
                    null
                } else {
                    afd.createInputStream().use(consume)
                }
            },
        )
    }
}
