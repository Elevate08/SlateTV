package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class AppChannelArtworkTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = "com.example.tv"
    private val poster = "content://com.example.tv.images/poster"
    private val thumbnail = "content://com.example.tv.images/thumb"

    @Test fun copiesContentArtworkIntoTheCacheAndReusesTheCachedFile() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val artwork = AppChannelArtwork({ LiveTvLogoCache(folder.root) }) { uri, pkg ->
            requests += uri to pkg
            byteArrayOf(1, 2, 3)
        }
        val program = AppProgramRow(1, owner, posterUri = poster, thumbnailUri = thumbnail)
        val result = requireNotNull(artwork.load(program))
        val file = File(URI(result))
        assertEquals(folder.root, file.parentFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(result, artwork.load(program))
        assertEquals(listOf(poster to owner), requests)
    }

    @Test fun unavailableContentPosterFallsBackToHttpsThumbnail() = runTest {
        val artwork = AppChannelArtwork({ LiveTvLogoCache(folder.root) }) { _, _ -> null }
        val web = "https://example.com/thumbnail"
        val program = AppProgramRow(1, owner, posterUri = poster, thumbnailUri = web)
        assertEquals(web, artwork.load(program))
    }

    @Test fun posterAndThumbnailShareOneTwoSecondBudgetPerItem() = runTest {
        val requests = mutableListOf<String>()
        val artwork = AppChannelArtwork({ LiveTvLogoCache(folder.root) }) { uri, _ ->
            requests += uri
            delay(1_500)
            if (uri == poster) null else byteArrayOf(1)
        }
        val program = AppProgramRow(1, owner, posterUri = poster, thumbnailUri = thumbnail)
        assertNull(artwork.load(program))
        assertEquals(LOGO_FETCH_TIMEOUT_MS, currentTime)
        assertEquals(listOf(poster, thumbnail), requests)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }
}
