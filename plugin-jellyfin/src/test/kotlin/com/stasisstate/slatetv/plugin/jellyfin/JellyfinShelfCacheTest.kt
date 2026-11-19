package com.stasisstate.slatetv.plugin.jellyfin

import com.stasisstate.slatetv.plugin.api.ShelfPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinShelfCacheTest {
    private val config = JellyfinConfig(serverUrl = "https://jf.example/", apiKey = "key")
    private val good = ShelfPayload(true, "Live TV · On Now", emptyList())
    private val good2 = ShelfPayload(true, "Live TV · Updated", emptyList())
    private val noChannels = ShelfPayload(false, "Live TV", emptyList(), "No Live TV channels found.")

    private class FakeFetcher {
        var calls = 0
        val responses = ArrayDeque<suspend () -> ShelfPayload>()
        suspend fun fetch(@Suppress("UNUSED_PARAMETER") config: JellyfinConfig): ShelfPayload {
            calls++
            return responses.removeFirst()()
        }
    }

    private fun TestScope.cache(): Pair<JellyfinShelfCache, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return JellyfinShelfCache(scope, { testScheduler.currentTime }) to scope
    }

    @Test
    fun confirmedSuccessAndUnavailableAreReturnedDirectly() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { good }
        assertEquals(good, cache.get(config, fetcher::fetch))
        // Fresh success served from cache without refetching.
        advanceTimeBy(44_000)
        assertEquals(good, cache.get(config, fetcher::fetch))
        assertEquals(1, fetcher.calls)

        // After the fresh window, a confirmed "no channels" is returned as available=false.
        advanceTimeBy(2_000)
        fetcher.responses += { noChannels }
        val result = cache.get(config, fetcher::fetch)
        assertNotNull(result)
        assertFalse(result!!.available)
        assertEquals("No Live TV channels found.", result.emptyReason)
        scope.cancel()
    }

    @Test
    fun coldTransientFailureReturnsNullNotUnavailable() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { throw IOException("network unreachable") }
        assertNull(cache.get(config, fetcher::fetch))
        scope.cancel()
    }

    @Test
    fun coldRefreshPastWaitBudgetReturnsNullThenServesResultWhenReady() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { delay(5_000); good }
        assertNull(cache.get(config, fetcher::fetch))
        assertEquals(1, fetcher.calls)
        // The in-flight refresh is joined, not restarted, and its success is cached.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(good, cache.get(config, fetcher::fetch))
        assertEquals(1, fetcher.calls)
        scope.cancel()
    }

    @Test
    fun fetchTimeoutIsTransient() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { delay(60_000); good }
        assertNull(cache.get(config, fetcher::fetch))
        advanceTimeBy(30_000)
        runCurrent()
        // 25s fetch timeout recorded as transient → still null (no stale success), never available=false.
        advanceTimeBy(3_000)
        fetcher.responses += { throw IOException("still down") }
        assertNull(cache.get(config, fetcher::fetch))
        scope.cancel()
    }

    @Test
    fun transientFailureNeverOverwritesLastGoodAndServesStaleWithinNinetySeconds() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { good }
        assertEquals(good, cache.get(config, fetcher::fetch))

        // Stale (46s) success + transient failure → stale success is served.
        advanceTimeBy(46_000)
        fetcher.responses += { throw IOException("dhcp") }
        assertEquals(good, cache.get(config, fetcher::fetch))
        assertEquals(2, fetcher.calls)

        // Within 3s backoff: no new attempt, stale success still served.
        advanceTimeBy(1_000)
        assertEquals(good, cache.get(config, fetcher::fetch))
        assertEquals(2, fetcher.calls)

        // After backoff: a slow refresh exceeding the wait budget still serves the stale success.
        advanceTimeBy(3_000)
        fetcher.responses += { delay(10_000); throw IOException("slow failure") }
        assertEquals(good, cache.get(config, fetcher::fetch))
        assertEquals(3, fetcher.calls)
        advanceTimeBy(10_000)
        runCurrent()

        // Past 90s since the last success, transient failures return null (not available=false).
        advanceTimeBy(30_000)
        fetcher.responses += { throw IOException("down") }
        assertNull(cache.get(config, fetcher::fetch))

        // Recovery replaces lastGood.
        advanceTimeBy(3_000)
        fetcher.responses += { good2 }
        assertEquals(good2, cache.get(config, fetcher::fetch))
        scope.cancel()
    }

    @Test
    fun transientBackoffDelaysRetryForThreeSeconds() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { throw IOException("down") }
        assertNull(cache.get(config, fetcher::fetch))
        advanceTimeBy(2_999)
        assertNull(cache.get(config, fetcher::fetch))
        assertEquals(1, fetcher.calls)
        advanceTimeBy(1)
        fetcher.responses += { good }
        assertEquals(good, cache.get(config, fetcher::fetch))
        assertEquals(2, fetcher.calls)
        scope.cancel()
    }

    @Test
    fun confirmedUnavailableClearsLastGood() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { good }
        cache.get(config, fetcher::fetch)
        advanceTimeBy(46_000)
        fetcher.responses += { noChannels }
        assertEquals(noChannels, cache.get(config, fetcher::fetch))
        advanceTimeBy(3_000)
        fetcher.responses += { throw IOException("down") }
        assertNull(cache.get(config, fetcher::fetch))
        scope.cancel()
    }

    @Test
    fun configChangeDropsCachedSuccess() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        fetcher.responses += { good }
        cache.get(config, fetcher::fetch)
        fetcher.responses += { throw IOException("down") }
        assertNull(cache.get(config.copy(serverUrl = "https://other.example/"), fetcher::fetch))
        assertEquals(2, fetcher.calls)
        scope.cancel()
    }

    @Test
    fun equalButDistinctConfigInstancesKeepRefreshing() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        val payloads = (1..4).map { ShelfPayload(true, "Live TV $it", emptyList()) }
        payloads.forEachIndexed { i, payload ->
            if (i > 0) advanceTimeBy(46_000)
            fetcher.responses += { payload }
            // Service loads a new JellyfinConfig instance on every Binder call.
            assertEquals(payload, cache.get(config.copy(), fetcher::fetch))
            assertEquals(i + 1, fetcher.calls)
            // Result is cached fresh (no refetch within 45s).
            assertEquals(payload, cache.get(config.copy(), fetcher::fetch))
            assertEquals(i + 1, fetcher.calls)
        }
        scope.cancel()
    }

    @Test
    fun concurrentCallersShareOneRefresh() = runTest {
        val (cache, scope) = cache()
        val fetcher = FakeFetcher()
        val gate = CompletableDeferred<Unit>()
        fetcher.responses += { gate.await(); good }
        val a = async { cache.get(config, fetcher::fetch) }
        val b = async { cache.get(config, fetcher::fetch) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(good, a.await())
        assertEquals(good, b.await())
        assertEquals(1, fetcher.calls)
        scope.cancel()
    }
}
