package com.stasisstate.slatetv.connector.extensions

import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackgroundPluginTest {
    private val servers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()
    private val clock = FakeClock()

    @After fun tearDown() = servers.forEach { it.shutdown() }

    private fun server() = loopbackServer().also { servers += it }

    private fun feed(vararg ids: String, host: String = "https://img.example.com") =
        """{"items":[${ids.joinToString(",") { """{"id":"$it","imageUrl":"$host/$it.jpg","title":"T$it"}""" }}]}"""

    private fun plugin(
        s: okhttp3.mockwebserver.MockWebServer,
        key: String? = null,
        refresh: Int = 900,
        authHeader: String? = "X-API-Key",
    ): ExtensionBackgroundPlugin {
        val base = s.base()
        val manifest = sampleManifest(
            type = ExtensionType.BACKGROUND,
            manifestUrl = base.resolve("/manifest.json").toString(),
            backgroundUrl = base.resolve("/backgrounds.json").toString(),
            shelfUrl = null, refresh = refresh, authHeaderName = authHeader,
        )
        return ExtensionBackgroundPlugin(installed(manifest), ExtensionClient(), { key }, clock, Random(7))
    }

    private val ctx = PluginRequestContext()

    @Test fun metadataIsNamespacedAndNotExternal() {
        val p = plugin(server())
        assertEquals("extjson:com.example.alpine-feed", p.metadata.id)
        assertFalse(p.metadata.isExternal)
        assertEquals("Alpine Gallery", p.metadata.name)
    }

    @Test fun cyclesWithoutRepeatsAndNeverImmediatelyRepeatsAcrossPasses() = runTest {
        val s = server()
        s.enqueue(json(feed("a", "b", "c", "d")))
        val p = plugin(s)
        var last: String? = null
        val ids = (1..12).map {
            val media = p.fetchNext(ctx.copy(lastMediaId = last))!!
            assertTrue(media.id != last)
            last = media.id
            media.id
        }
        for (pass in ids.chunked(4)) assertEquals(setOf("a", "b", "c", "d"), pass.toSet())
        assertEquals(1, s.requestCount)
    }

    @Test fun singleItemRepeatsWithoutError() = runTest {
        val s = server()
        s.enqueue(json(feed("only")))
        val p = plugin(s)
        assertEquals("only", p.fetchNext(ctx)!!.id)
        assertEquals("only", p.fetchNext(ctx.copy(lastMediaId = "only"))!!.id)
    }

    @Test fun prefetchReturnsDistinctItems() = runTest {
        val s = server()
        s.enqueue(json(feed("a", "b", "c")))
        val batch = plugin(s).prefetch(ctx, 3)
        assertEquals(setOf("a", "b", "c"), batch.map { it.id }.toSet())
    }

    @Test fun cachesUntilIntervalThenRefreshes() = runTest {
        val s = server()
        s.enqueue(json(feed("a")))
        s.enqueue(json(feed("z")))
        val p = plugin(s, refresh = 60)
        assertEquals("a", p.fetchNext(ctx)!!.id)
        clock.now += 59_000
        assertEquals("a", p.fetchNext(ctx)!!.id)
        assertEquals(1, s.requestCount)
        clock.now += 2_000
        assertEquals("z", p.fetchNext(ctx.copy(lastMediaId = "a"))!!.id)
        assertEquals(2, s.requestCount)
    }

    @Test fun keepsStaleCacheOnRefreshFailureWithBackoff() = runTest {
        val s = server()
        s.enqueue(json(feed("a")))
        s.enqueue(MockResponse().setResponseCode(500))
        val p = plugin(s, refresh = 60)
        assertEquals("a", p.fetchNext(ctx)!!.id)
        clock.now += 61_000
        assertEquals("a", p.fetchNext(ctx)!!.id) // refresh failed, stale served
        assertEquals("a", p.fetchNext(ctx)!!.id) // within backoff: no extra request
        assertEquals(2, s.requestCount)
    }

    @Test fun returnsNullWhenNeverLoadedAndRetriesImmediately() = runTest {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(503))
        s.enqueue(json(feed("a")))
        val p = plugin(s)
        assertNull(p.fetchNext(ctx))
        assertEquals("a", p.fetchNext(ctx)!!.id)
    }

    @Test fun emptyFeedIsRetriedAfterShortBackoffNotFullInterval() = runTest {
        val s = server()
        s.enqueue(json("""{"items":[]}"""))
        s.enqueue(json(feed("a")))
        val p = plugin(s, refresh = 86_400)
        assertNull(p.fetchNext(ctx))
        assertNull(p.fetchNext(ctx)) // within backoff, no extra request
        assertEquals(1, s.requestCount)
        clock.now += 31_000
        assertEquals("a", p.fetchNext(ctx)!!.id)
    }

    @Test fun emptyOrInvalidFeedYieldsNull() = runTest {
        val s = server()
        s.enqueue(json("""{"items":[]}"""))
        assertNull(plugin(s).fetchNext(ctx))
        val s2 = server()
        s2.enqueue(json("garbage"))
        assertNull(plugin(s2).fetchNext(ctx))
    }

    @Test fun authHeaderOnlyForManifestOriginImages() = runTest {
        val s = server()
        val same = "http://127.0.0.1:${s.port}"
        s.enqueue(json("""{"items":[
            {"id":"same","imageUrl":"$same/a.jpg"},
            {"id":"other-host","imageUrl":"https://img.example.com/b.jpg"},
            {"id":"other-port","imageUrl":"http://127.0.0.1:${s.port + 1}/c.jpg"},
            {"id":"other-scheme","imageUrl":"https://127.0.0.1:${s.port}/d.jpg"}]}"""))
        val p = plugin(s, key = "k1")
        val seen = (1..4).associate {
            val m = p.fetchNext(ctx)!!
            m.id to m.headers
        }
        assertEquals(mapOf("X-API-Key" to "k1"), seen["same"])
        assertTrue(seen["other-host"]!!.isEmpty())
        assertTrue(seen["other-port"]!!.isEmpty())
        assertTrue(seen["other-scheme"]!!.isEmpty())
    }

    @Test fun noHeadersWithoutKeyAndBearerDefault() = runTest {
        val s = server()
        val same = "http://127.0.0.1:${s.port}"
        s.enqueue(json("""{"items":[{"id":"a","imageUrl":"$same/a.jpg"}]}"""))
        assertTrue(plugin(s, key = null).fetchNext(ctx)!!.headers.isEmpty())
        val s2 = server()
        s2.enqueue(json("""{"items":[{"id":"a","imageUrl":"http://127.0.0.1:${s2.port}/a.jpg"}]}"""))
        val m = plugin(s2, key = "tok", authHeader = null).fetchNext(ctx)!!
        assertEquals(mapOf("Authorization" to "Bearer tok"), m.headers)
    }

    @Test fun feedRequestCarriesKeyAndRedirectToOtherOriginStripsIt() = runTest {
        val origin = server()
        val other = server()
        other.enqueue(json(feed("a")))
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.base().resolve("/feed").toString()))
        assertNotNull(plugin(origin, key = "k1").fetchNext(ctx))
        assertEquals("k1", origin.takeRequest().getHeader("X-API-Key"))
        assertNull(other.takeRequest().getHeader("X-API-Key"))
    }

    @Test fun rejectsNonHttpImageUrisAndCapsItems() = runTest {
        val s = server()
        val bad = listOf("file:///sdcard/a.jpg", "content://media/1", "javascript:alert(1)", "ambient://x", "intent://x", "data:image/png;base64,AA==")
        val items = bad.mapIndexed { i, u -> """{"id":"bad$i","imageUrl":"$u"}""" } +
            (1..250).map { """{"id":"ok$it","url":"https://img.example.com/$it.jpg"}""" }
        s.enqueue(json("""{"items":[${items.joinToString(",")}]}"""))
        val parsed = BackgroundFeedParser.parse("""{"items":[${items.joinToString(",")}]}""")
        // The six bad entries are skipped; the cap is applied to the first 200 entries read.
        assertEquals(194, parsed.size)
        assertTrue(parsed.all { it.imageUrl.startsWith("https://") })
        val p = plugin(s)
        repeat(30) { assertTrue(p.fetchNext(ctx)!!.imageUri.startsWith("https://img.example.com/")) }
    }

    @Test fun parsesAliasesTruncatesAndDedupes() {
        val long = "x".repeat(500)
        val parsed = BackgroundFeedParser.parse(
            """[{"url":"https://a.example/1.jpg","title":"$long","subtitle":"$long","width":1920,"height":1080,"id":"one"},
               {"id":"one","imageUrl":"https://a.example/2.jpg"},
               "https://a.example/3.jpg", 5, null]""",
        )
        assertEquals(2, parsed.size)
        assertEquals("one", parsed[0].id)
        assertEquals(120, parsed[0].title!!.length)
        assertEquals(160, parsed[0].subtitle!!.length)
        assertEquals(1920, parsed[0].width)
    }

    @Test fun preferNotNeededForPortraitFlag() = runTest {
        val s = server()
        s.enqueue(json("""{"items":[{"id":"p","imageUrl":"https://i.example/p.jpg","width":1080,"height":1920}]}"""))
        assertTrue(plugin(s).fetchNext(ctx)!!.isPortrait)
    }
}
