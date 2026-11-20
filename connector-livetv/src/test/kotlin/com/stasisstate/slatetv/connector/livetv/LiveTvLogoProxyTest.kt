package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiveTvLogoProxyTest {
    private val url = "content://android.media.tv/channel/1/logo"

    @Test fun namespaceUrlOrTagChangesProduceDistinctCacheKeys() {
        val sameKey = LiveTvLogoCache.cacheKeyFor("system-live-channels", url, "tag")
        assertEquals(sameKey, LiveTvLogoCache.cacheKeyFor("system-live-channels", url, "tag"))
        val otherNamespace = LiveTvLogoCache.cacheKeyFor("other", url, "tag")
        val otherUrl = LiveTvLogoCache.cacheKeyFor("system-live-channels", "content://android.media.tv/channel/2/logo", "tag")
        val otherTag = LiveTvLogoCache.cacheKeyFor("system-live-channels", url, "tag2")
        assertEquals(4, setOf(sameKey, otherNamespace, otherUrl, otherTag).size)
        assertTrue(Regex("[0-9a-f]{32}").matches(sameKey))
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
