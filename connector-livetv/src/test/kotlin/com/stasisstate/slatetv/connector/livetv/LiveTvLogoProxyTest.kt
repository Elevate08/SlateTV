package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiveTvLogoProxyTest {
    private val url = "https://jf.local/Items/1/Images/Primary"

    @Test fun credentialOrUserChangesProduceDistinctCacheKeys() {
        val base = LiveTvConfig("https://jf.local", "key-a")
        val sameKey = LiveTvLogoCache.cacheKeyFor(logoNamespace(base, "user1"), url, "tag")
        assertEquals(sameKey, LiveTvLogoCache.cacheKeyFor(logoNamespace(base, "user1"), url, "tag"))
        val otherKey = LiveTvLogoCache.cacheKeyFor(logoNamespace(base.copy(apiKey = "key-b"), "user1"), url, "tag")
        val otherUser = LiveTvLogoCache.cacheKeyFor(logoNamespace(base, "user2"), url, "tag")
        val otherServer = LiveTvLogoCache.cacheKeyFor(logoNamespace(base.copy(serverUrl = "https://other"), "user1"), url, "tag")
        assertEquals(4, setOf(sameKey, otherKey, otherUser, otherServer).size)
        assertTrue(Regex("[0-9a-f]{32}").matches(sameKey))
        assertFalse(logoNamespace(base, "user1").contains("key-a"))
    }

    @Test fun concurrencyIsBoundedAndTimeoutStartsAfterAdmission() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val channels = (1..12).map { it.toString() }
        // 12 items * 120ms each at 4 permits = ~360ms total, far above the 200ms per-item timeout;
        // queued items must still succeed because the timeout only counts after admission.
        val result = proxyLogos(
            channels, { it }, { "https://x/$it" }, Semaphore(4), timeoutMs = 200L,
        ) { id, _ ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(120)
                "file:///logos/$id"
            } finally {
                active.decrementAndGet()
            }
        }
        assertEquals(4, peak.get())
        assertFalse(result.hasMissingLogos)
        assertEquals(12, result.logos.values.count { it != null })
    }

    @Test fun slowFetchTimesOutAfterAdmissionAndIsReportedMissing() = runBlocking {
        val result = proxyLogos(
            listOf("fast", "slow", "none"), { it }, { if (it == "none") null else "https://x/$it" },
            Semaphore(4), timeoutMs = 100L,
        ) { id, _ ->
            if (id == "slow") delay(5_000)
            "file:///logos/$id"
        }
        assertEquals("file:///logos/fast", result.logos["fast"])
        assertNull(result.logos["slow"])
        assertNull(result.logos["none"])
        assertTrue(result.hasMissingLogos)
    }

    @Test fun channelsWithoutLogoUrlAreNotMissing() = runBlocking {
        val result = proxyLogos(listOf("a"), { it }, { null }, Semaphore(4)) { _, _ -> "x" }
        assertFalse(result.hasMissingLogos)
    }

    @Test fun partialLogoFailureUsesShorterTtl() {
        assertTrue(shelfFreshTtlMs(true, true) < shelfFreshTtlMs(true, false))
        assertEquals(SUCCESS_FRESH_MS, shelfFreshTtlMs(true, false))
        assertEquals(PARTIAL_LOGO_FRESH_MS, shelfFreshTtlMs(true, true))
        assertEquals(FAILURE_FRESH_MS, shelfFreshTtlMs(false, true))
    }
}
