package com.stasisstate.slatetv.connector.livetv

/**
 * Tracks Jellyfin channel paging by raw server offset so the "Load more" button and
 * the page loader always agree on whether another request is worthwhile, even when
 * pages contain duplicate channel IDs or the server returns an empty trailing page.
 */
internal class GuidePaginationState {
    var nextStartIndex: Int = 0
        private set
    var totalChannels: Int = 0
        private set
    var loadedCount: Int = 0
        private set
    var reachedEnd: Boolean = false
        private set

    val hasMore: Boolean get() = !reachedEnd && nextStartIndex < totalChannels

    /**
     * @param received raw item count the server returned for this page
     * @param addedUnique items from this page that were new (not duplicates of loaded channels)
     * @param reportedTotal the server's reported total channel count
     */
    fun onPageLoaded(received: Int, addedUnique: Int, reportedTotal: Int) {
        nextStartIndex += received
        loadedCount += addedUnique
        totalChannels = maxOf(reportedTotal, nextStartIndex, loadedCount)
        if (received == 0 || nextStartIndex >= reportedTotal) reachedEnd = true
    }
}
