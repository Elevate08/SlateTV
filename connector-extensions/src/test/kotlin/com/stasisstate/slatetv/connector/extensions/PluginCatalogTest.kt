package com.stasisstate.slatetv.connector.extensions

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PluginCatalogTest {
    private val source = "https://elevate08.github.io/slatetv-plugins/catalog.json".toHttpUrl()
    private val servers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()

    @After fun tearDown() = servers.forEach { it.shutdown() }

    private fun catalog(plugins: String, extra: String = "") =
        """{"schemaVersion":1,"name":"Test Plugins"$extra,"plugins":[$plugins]}"""

    private val remote = """{"id":"com.example.feed","kind":"remote","name":"Feed","type":"background",
        "version":"1.2.0","manifestUrl":"plugins/feed/manifest.json","preinstall":true,
        "manifest":{"schemaVersion":1,"id":"com.example.feed","name":"Feed","type":"background","backgroundEndpoint":"backgrounds.json"}}"""

    @Test fun relativeManifestUrlsResolveUnderProjectPagesPath() {
        val entry = CatalogParser.parse(catalog(remote), source).entries.single()
        assertEquals("https://elevate08.github.io/slatetv-plugins/plugins/feed/manifest.json", entry.manifestUrl)
        assertEquals(CatalogEntryKind.REMOTE, entry.kind)
        assertEquals(ExtensionType.BACKGROUND, entry.type)
        assertEquals("Test Plugins", entry.catalogName)
    }

    @Test fun untrustedCatalogsCannotPreinstallOrInlineManifests() {
        val entry = CatalogParser.parse(catalog(remote), source).entries.single()
        assertFalse(entry.preinstall)
        assertNull(entry.inlineManifest)
        val trusted = CatalogParser.parse(catalog(remote), source, trusted = true).entries.single()
        assertTrue(trusted.preinstall)
        assertTrue(trusted.inlineManifest!!.contains("backgroundEndpoint"))
    }

    @Test fun builtinEntriesNeedNoManifest() {
        val entry = CatalogParser.parse(catalog("""{"id":"immich","kind":"builtin","name":"Immich"}"""), source).entries.single()
        assertEquals(CatalogEntryKind.BUILTIN, entry.kind)
        assertNull(entry.manifestUrl)
    }

    @Test fun invalidEntriesAreSkippedNotFatal() {
        val entries = CatalogParser.parse(catalog(listOf(
            """{"id":"bad id!","name":"x","manifestUrl":"m.json"}""",
            """{"id":"no-name","manifestUrl":"m.json"}""",
            """{"id":"plain-http","name":"x","manifestUrl":"http://example.com/m.json"}""",
            """{"id":"weird-kind","kind":"apk","name":"x"}""",
            """{"id":"no-url","kind":"remote","name":"x"}""",
            """{"id":"ok","name":"Ok","manifestUrl":"https://other.example.com/m.json"}""",
            """{"id":"ok","name":"Duplicate","manifestUrl":"m.json"}""",
            "42",
        ).joinToString(",")), source).entries
        assertEquals(listOf("ok"), entries.map { it.id })
        assertEquals("Ok", entries.single().name)
    }

    @Test fun rejectsNonCatalogs() {
        listOf("[]", """{"plugins":[]}""", """{"schemaVersion":2,"plugins":[]}""", """{"schemaVersion":1}""", "nope").forEach {
            try {
                CatalogParser.parse(it, source)
                fail("accepted $it")
            } catch (_: ExtensionException) {
            }
        }
    }

    @Test fun capsEntryCount() {
        val many = (0 until 300).joinToString(",") { """{"id":"p$it","name":"P$it","manifestUrl":"m$it.json"}""" }
        assertEquals(CatalogParser.MAX_ENTRIES, CatalogParser.parse(catalog(many), source).entries.size)
    }

    @Test fun liveCatalogReplacesBundledSnapshotButKeepsSeedData() {
        val bundled = CatalogParser.parse(catalog(remote), source, trusted = true)
        val live = CatalogParser.parse(catalog(remote.replace("1.2.0", "2.0.0") + ""","""+
            """{"id":"new-one","name":"New","manifestUrl":"n.json"}"""), source)
        val other = CatalogParser.parse(catalog("""{"id":"com.example.feed","name":"Impostor","manifestUrl":"x.json"},""" +
            """{"id":"third","name":"Third","manifestUrl":"t.json"}"""), "https://example.org/c.json".toHttpUrl())
        val merged = CatalogParser.merge(bundled, listOf(live, other))
        assertEquals(listOf("com.example.feed", "new-one", "third"), merged.map { it.id })
        assertEquals("2.0.0", merged[0].version)
        assertTrue(merged[0].preinstall)
        assertEquals("Feed", merged[0].name)
    }

    @Test fun mergeWorksOffline() {
        val bundled = CatalogParser.parse(catalog(remote), source, trusted = true)
        assertEquals(listOf("com.example.feed"), CatalogParser.merge(bundled, emptyList()).map { it.id })
    }

    @Test fun builtInsDefaultToInstalledForUpgradingUsers() {
        val state = PluginInstallState(FakeKeyValueStore())
        assertTrue(state.isBuiltInInstalled("immich"))
        state.setBuiltInInstalled("immich", false)
        assertFalse(state.isBuiltInInstalled("immich"))
        assertTrue(state.isBuiltInInstalled("livetv"))
        state.setBuiltInInstalled("immich", true)
        assertTrue(state.isBuiltInInstalled("immich"))
    }

    @Test fun seedingInstallsOnceAndRespectsUninstall() {
        val store = ExtensionStore(FakeKeyValueStore(), FakeVault())
        val state = PluginInstallState(FakeKeyValueStore())
        val bundled = CatalogParser.parse(catalog(remote), source, trusted = true)

        BundledPlugins.seed(bundled, store, state)
        val seeded = store.get("com.example.feed")!!
        assertEquals("https://elevate08.github.io/slatetv-plugins/plugins/feed/backgrounds.json", seeded.manifest.backgroundUrl)

        store.remove("com.example.feed")
        BundledPlugins.seed(bundled, store, state) // next launch / app update
        assertNull(store.get("com.example.feed"))
    }

    @Test fun seedingSkipsMismatchedOrNonPreinstallEntries() {
        val store = ExtensionStore(FakeKeyValueStore(), FakeVault())
        val state = PluginInstallState(FakeKeyValueStore())
        val mismatched = remote.replace(""""id":"com.example.feed","name":"Feed","type"""", """"id":"com.example.other","name":"Feed","type"""")
        val notPreinstalled = remote.replace("com.example.feed", "com.example.later").replace(""""preinstall":true""", """"preinstall":false""")
        BundledPlugins.seed(CatalogParser.parse(catalog("$mismatched,$notPreinstalled"), source, trusted = true), store, state)
        assertTrue(store.list().isEmpty())
    }

    @Test fun seedingDoesNotOverwriteUserInstall() {
        val store = ExtensionStore(FakeKeyValueStore(), FakeVault())
        val mine = installed(sampleManifest(id = "com.example.feed", type = ExtensionType.BACKGROUND, shelfUrl = null), enabled = false)
        store.upsert(mine)
        BundledPlugins.seed(CatalogParser.parse(catalog(remote), source, trusted = true), store, PluginInstallState(FakeKeyValueStore()))
        assertEquals(mine.manifest, store.get("com.example.feed")!!.manifest)
    }

    @Test fun sourceStoreKeepsFirstPartyFirstAndValidates() {
        val sources = CatalogSourceStore(FakeKeyValueStore())
        assertEquals(listOf(DEFAULT_CATALOG_URL), sources.list())
        sources.add("https://example.org/catalog.json")
        sources.add("https://example.org/catalog.json")
        sources.add(DEFAULT_CATALOG_URL)
        assertEquals(listOf(DEFAULT_CATALOG_URL, "https://example.org/catalog.json"), sources.list())
        try {
            sources.add("http://example.org/catalog.json")
            fail("accepted cleartext public URL")
        } catch (_: IllegalArgumentException) {
        }
        sources.remove(DEFAULT_CATALOG_URL)
        sources.remove("https://example.org/catalog.json")
        assertEquals(listOf(DEFAULT_CATALOG_URL), sources.list())
    }

    @Test fun fetchCatalogSendsNoCredentials() {
        val s = loopbackServer().also { servers += it }
        s.enqueue(json(catalog("""{"id":"x","name":"X","manifestUrl":"x/manifest.json"}""")))
        val result = ExtensionClient().fetchCatalog(s.base().resolve("/repo/catalog.json").toString())
        assertEquals(s.base().resolve("/repo/x/manifest.json").toString(), result.entries.single().manifestUrl)
        val request = s.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-API-Key"))
    }

    @Test fun bundledAssetParsesAndSeedsCleanly() {
        val asset = listOf("src/main/assets/${BundledPlugins.ASSET}", "connector-extensions/src/main/assets/${BundledPlugins.ASSET}")
            .map(::File).first { it.exists() }
        val bundled = CatalogParser.parse(asset.readText(), DEFAULT_CATALOG_URL.toHttpUrl(), trusted = true)
        assertTrue(bundled.entries.map { it.id }.containsAll(listOf("immich", "homeassistant", "livetv")))
        bundled.entries.filter { it.kind == CatalogEntryKind.REMOTE }.forEach { entry ->
            val manifest = ManifestParser.parse(entry.inlineManifest!!, entry.manifestUrl!!.toHttpUrl())
            assertEquals(entry.id, manifest.id)
        }
    }
}
