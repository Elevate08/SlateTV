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

        // Move YouTube left once
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

        // Pin additional app
        prefs.pin("com.example.fifth")
        assertTrue("com.example.fifth" in prefs.getPinnedPackages())

        // Hide removes from pinned and adds to hidden
        prefs.hide("com.example.fifth")
        assertFalse("com.example.fifth" in prefs.getPinnedPackages())
        assertTrue("com.example.fifth" in prefs.getHiddenPackages())

        // Re-seeding does not overwrite user customization
        prefs.seedDefaultsIfNeeded(installed + "com.netflix.ninja")
        assertFalse("com.netflix.ninja" in prefs.getPinnedPackages())
    }

    @Test
    fun `WallpaperDiskCache stores metadata persists lastShown and evicts oldest entries`() {
        val cache = WallpaperDiskCache(
            cacheDir = tempFolder.newFolder("wallpapers"),
            maxBytes = 1024,
            maxEntries = 3,
        )

        val m1 = BackgroundMedia(id = "immich:1", imageUri = "http://x/1", subtitle = "Jan 2025")
        val m2 = BackgroundMedia(id = "immich:2", imageUri = "http://x/2", subtitle = "Feb 2025")
        val m3 = BackgroundMedia(id = "immich:3", imageUri = "http://x/3", subtitle = "Mar 2025")
        val m4 = BackgroundMedia(id = "immich:4", imageUri = "http://x/4", subtitle = "Apr 2025")

        cache.put(m1, ByteArray(100) { 1 })
        Thread.sleep(15)
        cache.put(m2, ByteArray(100) { 2 })
        Thread.sleep(15)
        val e3 = cache.put(m3, ByteArray(100) { 3 })
        Thread.sleep(15)
        cache.put(m4, ByteArray(100) { 4 })

        // Oldest entry (m1) should have been evicted due to maxEntries = 3
        assertNull(cache.get("immich:1"))
        assertNotNull(cache.get("immich:4"))
        assertEquals("Apr 2025", cache.get("immich:4")?.subtitle)

        // Mark last shown and verify instant restore lookup
        cache.markLastShown(e3)
        val restored = cache.getLastShown()
        assertNotNull(restored)
        assertEquals("immich:3", restored?.mediaId)
        assertEquals("Mar 2025", restored?.subtitle)
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
    fun `BitmapLoader calculateInSampleSize downsamples 4K images safely for 1080p`() {
        assertEquals(2, BitmapLoader.calculateInSampleSize(3840, 2160, 1920, 1080))
        assertEquals(1, BitmapLoader.calculateInSampleSize(1920, 1080, 1920, 1080))
        assertEquals(4, BitmapLoader.calculateInSampleSize(8000, 6000, 1920, 1080))
    }
}
