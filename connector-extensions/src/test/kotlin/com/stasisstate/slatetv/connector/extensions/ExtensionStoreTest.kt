package com.stasisstate.slatetv.connector.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionStoreTest {
    private val values = FakeKeyValueStore()
    private val vault = FakeVault()
    private fun store() = ExtensionStore(values, vault)

    @Test fun codecRoundTrip() {
        val a = installed(sampleManifest())
        val b = installed(
            sampleManifest(id = "feed-x", type = ExtensionType.BACKGROUND, shelfUrl = null, authHeaderName = null)
                .copy(autoDetected = true, refreshIntervalSeconds = 120),
            enabled = false,
        )
        val decoded = ExtensionStoreCodec.decode(ExtensionStoreCodec.encode(listOf(a, b)))
        assertEquals(listOf(a, b), decoded)
    }

    @Test fun decodeToleratesGarbage() {
        assertTrue(ExtensionStoreCodec.decode(null).isEmpty())
        assertTrue(ExtensionStoreCodec.decode("").isEmpty())
        assertTrue(ExtensionStoreCodec.decode("{not json").isEmpty())
        assertTrue(ExtensionStoreCodec.decode("""{"extensions":"nope"}""").isEmpty())
    }

    @Test fun decodeRevalidatesTamperedEntries() {
        val good = installed(sampleManifest())
        val encoded = ExtensionStoreCodec.encode(listOf(good))
        fun tampered(from: String, to: String) = ExtensionStoreCodec.decode(encoded.replace(from, to))
        assertTrue(tampered("https://example.com/manifest.json", "http://example.com/manifest.json").isEmpty())
        assertTrue(tampered("https://example.com/manifest.json", "file:///etc/passwd").isEmpty())
        assertTrue(tampered("https://example.com/backgrounds.json", "https://evil.example.net/backgrounds.json").isEmpty())
        assertTrue(tampered("X-API-Key", "Cookie").isEmpty())
        assertTrue(tampered("com.example.alpine-feed", "bad id").isEmpty())
        assertTrue(tampered("\"both\"", "\"weird\"").isEmpty())
    }

    @Test fun decodeDropsDuplicateIds() {
        val a = installed(sampleManifest())
        assertEquals(1, ExtensionStoreCodec.decode(ExtensionStoreCodec.encode(listOf(a, a))).size)
    }

    @Test fun persistenceAcrossStoreInstances() {
        store().upsert(installed(sampleManifest()), apiKey = "secret-1")
        val reopened = store()
        val list = reopened.list()
        assertEquals(1, list.size)
        assertTrue(list.single().hasApiKey)
        assertEquals("secret-1", reopened.apiKey("com.example.alpine-feed"))
        assertFalse(values.map.values.any { "secret-1" in it }) // secrets never touch the plain list
    }

    @Test fun enableDisableAndRemove() {
        val s = store()
        s.upsert(installed(sampleManifest()), "k")
        s.setEnabled("com.example.alpine-feed", false)
        assertFalse(s.get("com.example.alpine-feed")!!.enabled)
        s.setEnabled("com.example.alpine-feed", true)
        assertTrue(s.get("com.example.alpine-feed")!!.enabled)
        s.remove("com.example.alpine-feed")
        assertTrue(s.list().isEmpty())
        assertNull(s.apiKey("com.example.alpine-feed"))
    }

    @Test fun setAndClearApiKey() {
        val s = store()
        s.upsert(installed(sampleManifest()))
        assertFalse(s.list().single().hasApiKey)
        s.setApiKey("com.example.alpine-feed", "abc123")
        assertEquals("abc123", s.apiKey("com.example.alpine-feed"))
        s.setApiKey("com.example.alpine-feed", null)
        assertFalse(s.list().single().hasApiKey)
    }

    @Test fun upsertReplacesAndKeepsExistingKeyWhenNullPassed() {
        val s = store()
        s.upsert(installed(sampleManifest()), "keep")
        s.upsert(installed(sampleManifest().copy(version = "2.0.0")))
        assertEquals("2.0.0", s.list().single().manifest.version)
        assertEquals("keep", s.apiKey("com.example.alpine-feed"))
    }

    @Test fun reusedIdFromDifferentOriginDoesNotInheritStoredKey() {
        val s = store()
        s.upsert(installed(sampleManifest()), "origin-a-key")
        val hijack = sampleManifest(
            manifestUrl = "https://evil.example.net/manifest.json",
            backgroundUrl = "https://evil.example.net/backgrounds.json",
            shelfUrl = "https://evil.example.net/shelf.json",
        )
        s.upsert(installed(hijack))
        assertNull(s.apiKey("com.example.alpine-feed"))
        assertFalse(s.list().single().hasApiKey)
    }

    @Test fun sameOriginUpdateKeepsKeyButExplicitNewKeyAlwaysWins() {
        val s = store()
        s.upsert(installed(sampleManifest()), "old")
        s.upsert(installed(sampleManifest(manifestUrl = "https://example.com/v2/manifest.json")))
        assertEquals("old", s.apiKey("com.example.alpine-feed"))
        s.upsert(installed(sampleManifest(manifestUrl = "https://other.example.org/m.json",
            backgroundUrl = "https://other.example.org/b.json", shelfUrl = "https://other.example.org/s.json")), "new")
        assertEquals("new", s.apiKey("com.example.alpine-feed"))
    }

    @Test fun invalidKeysAndUnknownIdsRejected() {
        val s = store()
        try { s.upsert(installed(sampleManifest()), "bad key,\"") ; fail() } catch (_: IllegalArgumentException) {}
        try { s.setApiKey("nope", "abc") ; fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun registryExposesOnlyEnabledPluginsByType() {
        val s = store()
        s.upsert(installed(sampleManifest(id = "both", type = ExtensionType.BOTH)))
        s.upsert(installed(sampleManifest(id = "bg", type = ExtensionType.BACKGROUND, shelfUrl = null)))
        s.upsert(installed(sampleManifest(id = "shelf", type = ExtensionType.SHELF, backgroundUrl = null)))
        s.upsert(installed(sampleManifest(id = "off", type = ExtensionType.BOTH), enabled = false))
        val registry = ExtensionRegistry(s, ExtensionClient(), "host.pkg")
        assertEquals(4, registry.installedExtensions().size)
        assertEquals(setOf("extjson:both", "extjson:bg"), registry.backgroundPlugins().map { it.metadata.id }.toSet())
        assertEquals(setOf("extshelf:both", "extshelf:shelf"), registry.shelfPlugins().map { it.metadata.id }.toSet())
        // Instances are reused while the extension is unchanged, and dropped when disabled.
        val first = registry.backgroundPlugins().first { it.metadata.id == "extjson:bg" }
        assertTrue(first === registry.backgroundPlugins().first { it.metadata.id == "extjson:bg" })
        s.setEnabled("bg", false)
        assertEquals(setOf("extjson:both"), registry.backgroundPlugins().map { it.metadata.id }.toSet())
    }
}
