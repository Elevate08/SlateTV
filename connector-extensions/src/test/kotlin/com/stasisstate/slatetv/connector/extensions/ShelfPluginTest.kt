package com.stasisstate.slatetv.connector.extensions

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfPluginTest {
    private val servers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()
    private val clock = FakeClock()

    @After fun tearDown() = servers.forEach { it.shutdown() }

    private fun server() = loopbackServer().also { servers += it }

    private fun plugin(s: okhttp3.mockwebserver.MockWebServer, key: String? = null): ExtensionShelfPlugin {
        val base = s.base()
        val manifest = sampleManifest(
            type = ExtensionType.SHELF,
            manifestUrl = base.resolve("/manifest.json").toString(),
            backgroundUrl = null,
            shelfUrl = base.resolve("/shelf.json").toString(),
        )
        return ExtensionShelfPlugin(installed(manifest), ExtensionClient(), { key }, "host.pkg", clock)
    }

    private fun shelf(items: String, extra: String = "") =
        """{"available":true,"shelfTitle":"Tonight","items":[$items]$extra}"""

    @Test fun metadataNamespaced() {
        val p = plugin(server())
        assertEquals("extshelf:com.example.alpine-feed", p.metadata.id)
        assertEquals("host.pkg", p.metadata.packageName)
    }

    @Test fun parsesCardWithAllFields() = runTest {
        val s = server()
        s.enqueue(json(shelf("""{"id":"c1","title":"News","subtitle":"Live","channelNumber":"7.1","badge":"LIVE",
            "imageUrl":"https://img.example.com/n.png","progressPercent":42,"launchPackageName":"com.example.app",
            "launchUri":"https://example.com/watch"}""", ""","emptyReason":"none"""")))
        val data = plugin(s).fetchShelf()!!
        assertTrue(data.available)
        assertEquals("Tonight", data.shelfTitle)
        assertEquals("none", data.emptyReason)
        val c = data.items.single()
        assertEquals("c1", c.id); assertEquals("News", c.title); assertEquals("Live", c.subtitle)
        assertEquals("7.1", c.channelNumber); assertEquals("LIVE", c.badge); assertEquals(42, c.progressPercent)
        assertEquals("com.example.app", c.launchPackageName)
        assertEquals("https://example.com/watch", c.launchUri)
        assertEquals("https://img.example.com/n.png", c.imageUri)
        assertNull(c.launchActivityClassName)
    }

    @Test fun launchUriSchemeAllowlistBlocksDangerousSchemes() {
        val blocked = listOf(
            "intent://scan/#Intent;scheme=zxing;end", "file:///data/data/x", "content://contacts/people/1",
            "android-app://com.example/https/x", "javascript:alert(1)",
        )
        val body = shelf(
            (blocked.mapIndexed { i, u -> """{"id":"b$i","title":"t$i","launchUri":"$u"}""" } +
                """{"id":"m","title":"market","launchUri":"market://details?id=com.example"}""" +
                """{"id":"h","title":"http","launchUri":"http://example.com/x"}""").joinToString(","),
        )
        val items = ShelfFeedParser.parse(body, "fallback").items.associateBy { it.id }
        blocked.indices.forEach { assertNull(items["b$it"]!!.launchUri) }
        assertEquals("market://details?id=com.example", items["m"]!!.launchUri)
        assertEquals("http://example.com/x", items["h"]!!.launchUri)
    }

    @Test fun neverAllowsLaunchActivityClassName() {
        val body = shelf(
            """{"id":"x","title":"t","launchPackageName":"com.example.app",
               "launchActivityClassName":"com.evil.SecretActivity","launchExtras":{"a":"b"}}""",
        )
        val card = ShelfFeedParser.parse(body, "f").items.single()
        assertNull(card.launchActivityClassName)
        assertTrue(card.launchExtras.isEmpty())
        assertEquals("com.example.app", card.launchPackageName)
    }

    @Test fun invalidPackageNamesDropped() {
        val body = shelf(
            """{"id":"1","title":"a","launchPackageName":"../../etc"},
               {"id":"2","title":"b","launchPackageName":"nodots"},
               {"id":"3","title":"c","launchPackageName":"com.ok.pkg"}""",
        )
        val items = ShelfFeedParser.parse(body, "f").items.associateBy { it.id }
        assertNull(items["1"]!!.launchPackageName)
        assertNull(items["2"]!!.launchPackageName)
        assertEquals("com.ok.pkg", items["3"]!!.launchPackageName)
    }

    @Test fun stringTruncationAndImageUrlRules() {
        val long = "y".repeat(400)
        val body = shelf(
            """{"id":"1","title":"$long","subtitle":"$long","badge":"$long","channelNumber":"$long",
                "imageUrl":"file:///sdcard/x.png"},
               {"id":"2","title":"ok","imageUrl":"https://i.example/${"a".repeat(2100)}.png"},
               {"id":"3","title":"ok","imageUrl":"content://media/x"}""",
            ""","shelfTitle":"$long"""",
        ).replace(""""shelfTitle":"Tonight",""", "")
        val data = ShelfFeedParser.parse(body, "f")
        assertEquals(120, data.shelfTitle.length)
        val c = data.items.first { it.id == "1" }
        assertEquals(120, c.title.length)
        assertEquals(160, c.subtitle!!.length)
        assertEquals(24, c.badge!!.length)
        assertEquals(16, c.channelNumber!!.length)
        assertNull(c.imageUri)
        assertNull(data.items.first { it.id == "2" }.imageUri)
        assertNull(data.items.first { it.id == "3" }.imageUri)
    }

    @Test fun capsCardsAtTwentyFourAndSkipsUntitled() {
        val cards = (1..40).map { """{"id":"$it","title":"t$it"}""" } + """{"id":"untitled"}"""
        val data = ShelfFeedParser.parse(shelf(cards.joinToString(",")), "f")
        assertEquals(24, data.items.size)
        assertEquals("1", data.items.first().id)
        val withUntitled = ShelfFeedParser.parse(shelf("""{"id":"u"},{"id":"v","title":"  "},{"id":"w","title":"ok"}"""), "f")
        assertEquals(listOf("w"), withUntitled.items.map { it.id })
    }

    @Test fun progressOutOfRangeBecomesUnknown() {
        val data = ShelfFeedParser.parse(shelf("""{"id":"1","title":"a","progressPercent":150},{"id":"2","title":"b","progressPercent":-5},{"id":"3","title":"c"}"""), "f")
        assertTrue(data.items.all { it.progressPercent == -1 })
    }

    @Test fun unavailableShelfHasNoItems() {
        val data = ShelfFeedParser.parse("""{"available":false,"emptyReason":"Nothing on","items":[{"id":"1","title":"a"}]}""", "Fallback")
        assertFalse(data.available)
        assertTrue(data.items.isEmpty())
        assertEquals("Fallback", data.shelfTitle)
        assertEquals("Nothing on", data.emptyReason)
    }

    @Test fun cachesBrieflyThenRefetches() = runTest {
        val s = server()
        s.enqueue(json(shelf("""{"id":"1","title":"first"}""")))
        s.enqueue(json(shelf("""{"id":"2","title":"second"}""")))
        val p = plugin(s)
        assertEquals("first", p.fetchShelf()!!.items.single().title)
        clock.now += 29_000
        assertEquals("first", p.fetchShelf()!!.items.single().title)
        assertEquals(1, s.requestCount)
        clock.now += 2_000
        assertEquals("second", p.fetchShelf()!!.items.single().title)
    }

    @Test fun preservesLastGoodShelfForNinetySecondsOnErrors() = runTest {
        val s = server()
        s.enqueue(json(shelf("""{"id":"1","title":"good"}""")))
        s.enqueue(MockResponse().setResponseCode(500))
        s.enqueue(json("not json"))
        s.enqueue(MockResponse().setResponseCode(500))
        val p = plugin(s)
        assertEquals("good", p.fetchShelf()!!.items.single().title)
        clock.now += 40_000
        assertEquals("good", p.fetchShelf()!!.items.single().title) // HTTP 500, preserved
        clock.now += 40_000
        assertEquals("good", p.fetchShelf()!!.items.single().title) // invalid JSON at +80s, preserved
        clock.now += 11_000
        assertNull(p.fetchShelf()) // +91s since last good
    }

    @Test fun nullWhenNeverLoaded() = runTest {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(404))
        assertNull(plugin(s).fetchShelf())
    }

    @Test fun imageHeadersOnlyForManifestOrigin() = runTest {
        val s = server()
        s.enqueue(json(shelf("""{"id":"1","title":"a","imageUrl":"http://127.0.0.1:${s.port}/a.png"},
            {"id":"2","title":"b","imageUrl":"https://cdn.example.com/b.png"}""")))
        val data = plugin(s, key = "k1").fetchShelf()!!
        assertEquals(mapOf("X-API-Key" to "k1"), data.items.first { it.id == "1" }.imageHeaders)
        assertTrue(data.items.first { it.id == "2" }.imageHeaders.isEmpty())
        assertEquals("k1", s.takeRequest().getHeader("X-API-Key"))
    }

    @Test fun invalidShelfShapeIsError() {
        assertNotNull(runCatching { ShelfFeedParser.parse("[]", "f") }.exceptionOrNull())
    }
}
