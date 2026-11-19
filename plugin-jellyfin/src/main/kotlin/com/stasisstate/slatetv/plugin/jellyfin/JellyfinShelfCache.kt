package com.stasisstate.slatetv.plugin.jellyfin

import com.stasisstate.slatetv.plugin.api.ShelfPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Caches Jellyfin Live TV shelf results across Binder calls.
 *
 * Results are split into three kinds:
 * - **Success** (`available == true`): stored as [lastGood] and served fresh for [successFreshMs],
 *   then as a stale fallback for up to [successStaleMs] while a refresh is in flight or failing.
 * - **Confirmed unavailable** (the fetcher returned `available == false`: Live TV disabled, no
 *   channels): returned as-is so the host hides the shelf immediately. Clears [lastGood].
 * - **Transient** (fetch threw, timed out, or is still running past [waitBudgetMs]): never
 *   overwrites [lastGood]. Returns an eligible stale [lastGood], otherwise `null` so the host keeps
 *   its previous shelf and retries shortly. Further refreshes back off for [failureBackoffMs].
 */
internal class JellyfinShelfCache(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val waitBudgetMs: Long = 3_300,
    private val fetchTimeoutMs: Long = 25_000,
    private val successFreshMs: Long = SUCCESS_FRESH_MS,
    private val partialLogoFreshMs: Long = PARTIAL_LOGO_FRESH_MS,
    private val successStaleMs: Long = 90_000,
    private val failureBackoffMs: Long = FAILURE_FRESH_MS,
) {
    private sealed interface Outcome {
        data class Success(val payload: ShelfPayload, val hasMissingLogos: Boolean = false) : Outcome
        data class Confirmed(val payload: ShelfPayload) : Outcome
        data object Transient : Outcome
    }

    private val lock = Any()
    private var snapshot: JellyfinConfig? = null
    private var epoch = 0
    private var pending: Deferred<Outcome>? = null
    private var lastGood: ShelfPayload? = null
    private var lastGoodAt = 0L
    private var lastOutcome: Outcome? = null
    private var lastOutcomeAt = 0L

    /**
     * @param fetch returns a confirmed payload (`available` true or false); throws on transient errors.
     * @return the payload to serve, or `null` for a transient failure with no eligible cached success.
     */
    suspend fun get(config: JellyfinConfig, fetch: suspend (JellyfinConfig) -> ShelfPayload): ShelfPayload? =
        getFetch(config) { ShelfFetch(fetch(it)) }

    suspend fun getFetch(config: JellyfinConfig, fetch: suspend (JellyfinConfig) -> ShelfFetch): ShelfPayload? {
        val refresh = synchronized(lock) {
            if (snapshot != config) {
                pending?.cancel()
                pending = null
                lastGood = null
                lastOutcome = null
                snapshot = config
                epoch++
            }
            val now = clock()
            val outcomeAge = now - lastOutcomeAt
            when (val outcome = lastOutcome) {
                is Outcome.Success -> {
                    val ttl = if (outcome.hasMissingLogos) partialLogoFreshMs else successFreshMs
                    if (outcomeAge < ttl) return outcome.payload
                }
                is Outcome.Confirmed -> if (outcomeAge < failureBackoffMs) return outcome.payload
                Outcome.Transient -> if (outcomeAge < failureBackoffMs && pending == null) return staleGoodLocked(now)
                null -> Unit
            }
            pending ?: startRefreshLocked(config, fetch)
        }
        return when (val outcome = withTimeoutOrNull(waitBudgetMs) { refresh.await() }) {
            is Outcome.Success -> outcome.payload
            is Outcome.Confirmed -> outcome.payload
            Outcome.Transient, null -> synchronized(lock) { staleGoodLocked(clock()) }
        }
    }

    private fun staleGoodLocked(now: Long): ShelfPayload? =
        lastGood?.takeIf { now - lastGoodAt <= successStaleMs }

    private fun startRefreshLocked(
        config: JellyfinConfig,
        fetch: suspend (JellyfinConfig) -> ShelfFetch,
    ): Deferred<Outcome> {
        // Compare by epoch, not config identity: callers load a fresh (equal) config per call.
        val generation = epoch
        return scope.async(start = CoroutineStart.LAZY) {
            val outcome = try {
                val result = withTimeout(fetchTimeoutMs) { fetch(config) }
                if (result.payload.available) {
                    Outcome.Success(result.payload, result.hasMissingLogos)
                } else {
                    Outcome.Confirmed(result.payload)
                }
            } catch (_: TimeoutCancellationException) {
                Outcome.Transient
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Outcome.Transient
            }
            synchronized(lock) {
                if (epoch == generation) {
                    val now = clock()
                    when (outcome) {
                        is Outcome.Success -> {
                            lastGood = outcome.payload
                            lastGoodAt = now
                        }
                        is Outcome.Confirmed -> lastGood = null
                        Outcome.Transient -> Unit // never overwrite lastGood on a transient failure
                    }
                    lastOutcome = outcome
                    lastOutcomeAt = now
                    pending = null
                }
            }
            outcome
        }.also {
            pending = it
            it.start()
        }
    }
}
