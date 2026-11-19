package com.stasisstate.slatetv.connector.livetv

import org.junit.Assert.*
import org.junit.Test

class GuidePaginationStateTest {
    @Test fun initialStateHasNoMore() {
        assertFalse(GuidePaginationState().hasMore)
    }

    @Test fun multiPageProgressionHasMoreUntilFinalPage() {
        val state = GuidePaginationState()
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 250)
        assertTrue(state.hasMore)
        assertEquals(100, state.nextStartIndex)
        assertEquals(250, state.totalChannels)
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 250)
        assertTrue(state.hasMore)
        state.onPageLoaded(received = 50, addedUnique = 50, reportedTotal = 250)
        assertFalse(state.hasMore)
        assertEquals(250, state.loadedCount)
        assertEquals(250, state.nextStartIndex)
    }

    @Test fun singlePageCoveringTotalHasNoMore() {
        val state = GuidePaginationState()
        state.onPageLoaded(received = 40, addedUnique = 40, reportedTotal = 40)
        assertFalse(state.hasMore)
    }

    @Test fun duplicateIdsAcrossPagesStillTerminate() {
        val state = GuidePaginationState()
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 200)
        assertTrue(state.hasMore)
        // Second page repeats 20 already-loaded channels: only 80 unique.
        state.onPageLoaded(received = 100, addedUnique = 80, reportedTotal = 200)
        assertEquals(180, state.loadedCount)
        assertEquals(200, state.nextStartIndex)
        assertFalse("raw offset reached total; no dead button", state.hasMore)
    }

    @Test fun entirelyDuplicatePageWithRemainingOffsetKeepsPaging() {
        val state = GuidePaginationState()
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 300)
        state.onPageLoaded(received = 100, addedUnique = 0, reportedTotal = 300)
        assertTrue(state.hasMore)
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 300)
        assertFalse(state.hasMore)
    }

    @Test fun emptyTrailingPageEndsImmediately() {
        val state = GuidePaginationState()
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 500)
        assertTrue(state.hasMore)
        state.onPageLoaded(received = 0, addedUnique = 0, reportedTotal = 500)
        assertFalse(state.hasMore)
        assertTrue(state.reachedEnd)
    }

    @Test fun serverTotalUnderReportingDoesNotHideLoadedCount() {
        val state = GuidePaginationState()
        state.onPageLoaded(received = 100, addedUnique = 100, reportedTotal = 60)
        assertEquals(100, state.totalChannels)
        assertFalse(state.hasMore)
    }
}
