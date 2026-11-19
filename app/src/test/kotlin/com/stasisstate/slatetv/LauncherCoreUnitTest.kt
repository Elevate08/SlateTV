package com.stasisstate.slatetv

import com.stasisstate.slatetv.apps.DockPreferences
import com.stasisstate.slatetv.apps.DockStorage
import com.stasisstate.slatetv.wallpaper.BitmapLoader
import com.stasisstate.slatetv.wallpaper.WallpaperDiskCache
import com.stasisstate.slatetv.wallpaper.plugins.AmbientSlatePlugin
import com.stasisstate.slatetv.wallpaper.plugins.PluginRegistry
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LauncherCoreUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryDockStorage : DockStorage {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    }

    @Test
    fun `DockPreferences seeds popular TV apps once and supports pin reorder hide`() {
        val prefs = DockPreferences(InMemoryDockStorage())
        val installed = listOf(
            "com.example.random",
            "com.teamsmart.videomanager.tv",
            "com.plexapp.android",
            "com.google.android.youtube.tv",
            "com.example.fifth",
        )
        prefs.seedDefaultsIfNeeded(installed)
        assertEquals(
            listOf(
                "com.plexapp.android",
                "com.teamsmart.videomanager.tv",
                "com.google.android.youtube.tv",
                "com.example.random",
            ),
            prefs.getPinnedPackages(),
        )

        assertTrue(prefs.movePinned("com.google.android.youtube.tv", -1))
        assertEquals(
            listOf(
                "com.plexapp.android",
                "com.google.android.youtube.tv",
                "com.teamsmart.videomanager.tv",
                "com.example.random",
            ),
            prefs.getPinnedPackages(),
        )

        prefs.pin("com.example.fifth")
        assertTrue("com.example.fifth" in prefs.getPinnedPackages())

        prefs.hide("com.example.fifth")
        assertFalse("com.example.fifth" in prefs.getPinnedPackages())
        assertTrue("com.example.fifth" in prefs.getHiddenPackages())

        prefs.seedDefaultsIfNeeded(installed + "com.netflix.ninja")
        assertFalse("com.netflix.ninja" in prefs.getPinnedPackages())
    }

    @Test
    fun `WallpaperDiskCache scopes lastShown per plugin protects it from eviction and enforces byte cap`() {
        val dir = tempFolder.newFolder("wallpapers")
        val cache = WallpaperDiskCache(
            cacheDir = dir,
            maxBytes = 1024,
            maxEntries = 3,
        )

        val m1 = BackgroundMedia(id = "local:1", imageUri = "http://x/1", subtitle = "Jan 2025")
        val m2 = BackgroundMedia(id = "local:2", imageUri = "http://x/2", subtitle = "Feb 2025")
        val m3 = BackgroundMedia(id = "local:3", imageUri = "http://x/3", subtitle = "Mar 2025")
        val m4 = BackgroundMedia(id = "local:4", imageUri = "http://x/4", subtitle = "Apr 2025")

        val e1 = cache.put(m1, ByteArray(100) { 1 })
        cache.markLastShown("builtin.local", e1)
        Thread.sleep(15)
        cache.put(m2, ByteArray(100) { 2 })
        Thread.sleep(15)
        cache.put(m3, ByteArray(100) { 3 })
        Thread.sleep(15)
        cache.put(m4, ByteArray(100) { 4 })

        // e1 is protected because it is marked lastShown; m2 is evicted instead
        assertNotNull(cache.get("local:1"))
        assertNull(cache.get("local:2"))
        assertEquals("Jan 2025", cache.getLastShown("builtin.local")?.subtitle)
        assertNull(cache.getLastShown("builtin.local_folder"))

        // Streaming cap rejects oversized payloads cleanly
        val tooBig = BackgroundMedia(id = "local:huge", imageUri = "http://x/huge")
        assertNull(cache.putStream(tooBig, ByteArray(500).inputStream(), maxBytesPerItem = 200))
        assertNull(cache.get("local:huge"))

        // Removing corrupt entry deletes it
        cache.remove("local:4")
        assertNull(cache.get("local:4"))
    }

    @Test
    fun `WallpaperDiskCache rejects path traversal in lastShown pointer`() {
        val dir = tempFolder.newFolder("wallpapers_sec")
        val cache = WallpaperDiskCache(dir)
        val entry = cache.put(BackgroundMedia(id = "ok", imageUri = "http://x/1"), ByteArray(16) { 7 })
        cache.markLastShown("plugin.test", entry)
        // Tamper pointer file with path traversal
        val ptr = dir.listFiles()!!.first { it.name.startsWith("last_shown_") }
        ptr.writeText("""{"id":"bad","file":"../outside.txt"}""")
        File(dir.parentFile, "outside.txt").writeText("secret")

        assertNull(cache.getLastShown("plugin.test"))
    }

    @Test
    fun `PluginRegistry resolves built-in plugins and falls back to Ambient Slate`() {
        val fakeLocal = object : BackgroundPlugin {
            override val metadata = PluginMetadata("builtin.local", "Local Photos", "desc", "1.0")
            override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? = null
        }
        val registry = PluginRegistry(context = null, localPlugin = fakeLocal)
        assertEquals("builtin.local", registry.getPluginById("builtin.local").metadata.id)
        assertEquals(AmbientSlatePlugin.PLUGIN_ID, registry.getPluginById("nonexistent.plugin").metadata.id)
    }

    private fun stubPlugin(id: String) = object : BackgroundPlugin {
        override val metadata = PluginMetadata(id, id, "desc", "1.0", isExternal = true)
        override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? = null
    }

    @Test
    fun `PluginRegistry resolves unambiguous legacy aliases and rejects ambiguous ones`() {
        val registry = PluginRegistry(context = null)
        val a = stubPlugin("ext:a/.Svc")
        val b = stubPlugin("ext:b/.Svc")
        val c = stubPlugin("ext:c/.Svc")
        registry.publishExternal(
            listOf(a, b, c),
            listOf("legacy.unique" to "ext:a/.Svc", "legacy.dup" to "ext:b/.Svc", "legacy.dup" to "ext:c/.Svc"),
        )
        org.junit.Assert.assertSame(a, registry.getPluginById("legacy.unique"))
        org.junit.Assert.assertSame(a, registry.getPluginById("ext:a/.Svc"))
        assertEquals(mapOf("legacy.unique" to "ext:a/.Svc"), registry.externalSnapshot.legacyIdAliases)
        assertEquals(AmbientSlatePlugin.PLUGIN_ID, registry.getPluginById("legacy.dup").metadata.id)
    }

    @Test
    fun `PluginRegistry refuses legacy aliases that shadow built-in or canonical IDs`() {
        val registry = PluginRegistry(context = null)
        val a = stubPlugin("ext:a/.Svc")
        registry.publishExternal(
            listOf(a),
            listOf(AmbientSlatePlugin.PLUGIN_ID to "ext:a/.Svc", "ext:a/.Svc" to "ext:a/.Svc"),
        )
        assertTrue(registry.externalSnapshot.legacyIdAliases.isEmpty())
        assertEquals(AmbientSlatePlugin.PLUGIN_ID, registry.getPluginById(AmbientSlatePlugin.PLUGIN_ID).metadata.id)
    }

    @Test
    fun `PluginRegistry publishes plugins and aliases as one atomic snapshot`() {
        val registry = PluginRegistry(context = null)
        val a = stubPlugin("ext:a/.Svc")
        registry.publishExternal(listOf(a), listOf("legacy.a" to "ext:a/.Svc"))
        val first = registry.externalSnapshot
        org.junit.Assert.assertSame(a, registry.getPluginById("legacy.a"))

        val b = stubPlugin("ext:b/.Svc")
        registry.publishExternal(listOf(b), emptyList())
        // Old snapshot is immutable; new snapshot never mixes old aliases with new plugins
        assertEquals(mapOf("legacy.a" to "ext:a/.Svc"), first.legacyIdAliases)
        assertEquals(listOf<BackgroundPlugin>(a), first.plugins)
        assertTrue(registry.externalSnapshot.legacyIdAliases.isEmpty())
        assertEquals(AmbientSlatePlugin.PLUGIN_ID, registry.getPluginById("legacy.a").metadata.id)
        org.junit.Assert.assertSame(b, registry.getPluginById("ext:b/.Svc"))
    }

    @Test
    fun `BitmapLoader calculateInSampleSize bounds both 4K and extreme panoramas`() {
        assertEquals(2, BitmapLoader.calculateInSampleSize(3840, 2160, 1920, 1080))
        assertEquals(1, BitmapLoader.calculateInSampleSize(1920, 1080, 1920, 1080))
        assertEquals(4, BitmapLoader.calculateInSampleSize(8000, 6000, 1920, 1080))
        // Extreme 12000x1000 panorama (12M pixels) must be downsampled to <= 4.5M pixels
        val panoSample = BitmapLoader.calculateInSampleSize(12000, 1000, 1920, 1080)
        assertTrue("Panorama must be downsampled (got $panoSample)", panoSample >= 2)
    }

    @Test
    fun `NetworkClientFactory 303 POST redirect becomes GET and strips body representation headers`() {
        val captured = java.util.concurrent.LinkedBlockingQueue<String>()
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val serverThread = Thread {
                repeat(2) { index ->
                    listener.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val head = StringBuilder()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            head.append(line).append("\n")
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toInt()
                            }
                        }
                        repeat(contentLength) { input.read() }
                        captured.offer(head.toString())
                        val response = if (index == 0) {
                            "HTTP/1.1 303 See Other\r\nLocation: /done\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        socket.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = com.stasisstate.slatetv.NetworkClientFactory.defaultClient()
            val request = okhttp3.Request.Builder()
                .url("http://127.0.0.1:${listener.localPort}/submit")
                .header("Content-Encoding", "identity")
                .header("Content-Language", "en")
                .header("Transfer-Encoding", "identity")
                .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            serverThread.join(5000)

            val first = captured.poll(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertTrue(first, first.startsWith("POST /submit"))
            assertTrue(first, first.contains("Content-Type: application/json", ignoreCase = true))
            val second = captured.poll(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertTrue(second, second.startsWith("GET /done"))
            for (name in listOf("Content-Type", "Content-Length", "Content-Encoding", "Content-Language", "Transfer-Encoding")) {
                assertFalse("$name leaked: $second", second.contains("$name:", ignoreCase = true))
            }
        }
    }

    @Test
    fun `BitmapLoader parseLocalFileUri accepts both single-slash and triple-slash file URIs`() {
        val sample = tempFolder.newFile("banff lake.jpg")
        assertEquals(sample.canonicalPath, BitmapLoader.parseLocalFileUri(sample.toURI().toString())?.canonicalPath)
        assertEquals(sample.canonicalPath, BitmapLoader.parseLocalFileUri("file://${sample.toURI().rawPath}")?.canonicalPath)
        assertNull(BitmapLoader.parseLocalFileUri("https://example.com/photo.jpg"))
    }

    @Test
    fun `TipJar exposes unrestricted optional tip tiers 1 3 5 10 and 20`() {
        assertEquals(
            listOf("tip_1", "tip_3", "tip_5", "tip_10", "tip_20"),
            com.stasisstate.slatetv.billing.TipJar.PRODUCT_IDS,
        )
    }
}
