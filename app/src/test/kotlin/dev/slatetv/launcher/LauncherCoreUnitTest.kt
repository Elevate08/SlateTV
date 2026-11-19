package dev.slatetv.launcher

import dev.slatetv.launcher.apps.DockPreferences
import dev.slatetv.launcher.apps.DockStorage
import dev.slatetv.launcher.wallpaper.BitmapLoader
import dev.slatetv.launcher.wallpaper.WallpaperDiskCache
import dev.slatetv.launcher.wallpaper.plugins.AmbientSlatePlugin
import dev.slatetv.launcher.wallpaper.plugins.PluginRegistry
import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.BackgroundPlugin
import dev.slatetv.plugin.api.PluginMetadata
import dev.slatetv.plugin.api.PluginRequestContext
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
            "org.jellyfin.androidtv",
            "com.plexapp.android",
            "com.google.android.youtube.tv",
            "com.example.fifth",
        )
        prefs.seedDefaultsIfNeeded(installed)
        assertEquals(
            listOf(
                "com.plexapp.android",
                "org.jellyfin.androidtv",
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
                "org.jellyfin.androidtv",
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

        val m1 = BackgroundMedia(id = "immich:1", imageUri = "http://x/1", subtitle = "Jan 2025")
        val m2 = BackgroundMedia(id = "immich:2", imageUri = "http://x/2", subtitle = "Feb 2025")
        val m3 = BackgroundMedia(id = "immich:3", imageUri = "http://x/3", subtitle = "Mar 2025")
        val m4 = BackgroundMedia(id = "immich:4", imageUri = "http://x/4", subtitle = "Apr 2025")

        val e1 = cache.put(m1, ByteArray(100) { 1 })
        cache.markLastShown("builtin.immich", e1)
        Thread.sleep(15)
        cache.put(m2, ByteArray(100) { 2 })
        Thread.sleep(15)
        cache.put(m3, ByteArray(100) { 3 })
        Thread.sleep(15)
        cache.put(m4, ByteArray(100) { 4 })

        // e1 is protected because it is marked lastShown; m2 is evicted instead
        assertNotNull(cache.get("immich:1"))
        assertNull(cache.get("immich:2"))
        assertEquals("Jan 2025", cache.getLastShown("builtin.immich")?.subtitle)
        assertNull(cache.getLastShown("builtin.local_folder"))

        // Streaming cap rejects oversized payloads cleanly
        val tooBig = BackgroundMedia(id = "immich:huge", imageUri = "http://x/huge")
        assertNull(cache.putStream(tooBig, ByteArray(500).inputStream(), maxBytesPerItem = 200))
        assertNull(cache.get("immich:huge"))

        // Removing corrupt entry deletes it
        cache.remove("immich:4")
        assertNull(cache.get("immich:4"))
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
        val fakeImmich = object : BackgroundPlugin {
            override val metadata = PluginMetadata("builtin.immich", "Immich Photos", "desc", "1.0")
            override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? = null
        }
        val registry = PluginRegistry(context = null, immichPlugin = fakeImmich)
        assertEquals("builtin.immich", registry.getPluginById("builtin.immich").metadata.id)
        assertEquals(AmbientSlatePlugin.PLUGIN_ID, registry.getPluginById("nonexistent.plugin").metadata.id)
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
}
