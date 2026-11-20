package com.stasisstate.slatetv.connector.extensions

import android.content.Context
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plugin catalogs ("plugin repositories"): a static `catalog.json` listing plugins that the
 * launcher can install, uninstall and reinstall. Two kinds of entry exist:
 *
 *  - [CatalogEntryKind.REMOTE]: an Open JSON Extension. Installing fetches its manifest from
 *    [CatalogEntry.manifestUrl] and goes through the normal confirmation screen.
 *  - [CatalogEntryKind.BUILTIN]: a native connector whose code ships inside the APK (Android
 *    cannot download code). The catalog entry only lets users install/uninstall it.
 *
 * The first-party catalog is hosted on GitHub Pages ([DEFAULT_CATALOG_URL]); a snapshot of it is
 * bundled in assets so the catalog works offline and pre-installed plugins can be seeded.
 */
enum class CatalogEntryKind(val wire: String) {
    BUILTIN("builtin"),
    REMOTE("remote");

    companion object {
        fun fromWire(value: String?): CatalogEntryKind? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class CatalogEntry(
    val id: String,
    val kind: CatalogEntryKind,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val type: ExtensionType?,
    /** Absolute manifest URL (remote entries only). */
    val manifestUrl: String?,
    /** Install automatically on first run (bundled catalog only). */
    val preinstall: Boolean,
    /** Raw manifest JSON shipped inside the bundled catalog, used to seed without network. */
    val inlineManifest: String?,
    /** Catalog URL this entry came from. */
    val catalogUrl: String,
    val catalogName: String,
)

data class PluginCatalog(val name: String, val url: String, val entries: List<CatalogEntry>)

internal object CatalogParser {
    const val SCHEMA_VERSION = 1
    const val MAX_ENTRIES = 200

    /**
     * Parses a catalog served from [source]. Relative manifest URLs resolve against [source] (so a
     * GitHub project Pages site can use `plugins/x/manifest.json`). [trusted] is true only for the
     * snapshot bundled in the APK: it alone may carry inline manifests and `preinstall` flags.
     */
    fun parse(body: String, source: HttpUrl, trusted: Boolean = false): PluginCatalog {
        val json = parseUntrustedJson(body) as? JSONObject ?: throw ExtensionException("Not a plugin catalog")
        val version = (json.opt("schemaVersion") as? Number)?.toInt()
        if (version != SCHEMA_VERSION) throw ExtensionException("Unsupported plugin catalog version")
        val catalogName = ExtensionUrlPolicy.text(json.opt("name") as? String, 80) ?: source.host
        val array = json.opt("plugins") as? JSONArray ?: throw ExtensionException("Plugin catalog has no plugins list")
        val seen = HashSet<String>()
        val entries = ArrayList<CatalogEntry>()
        for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val entry = runCatching { parseEntry(array.optJSONObject(i), source, catalogName, trusted) }.getOrNull() ?: continue
            if (seen.add(entry.id)) entries += entry
        }
        return PluginCatalog(catalogName, source.toString(), entries)
    }

    private fun parseEntry(json: JSONObject?, source: HttpUrl, catalogName: String, trusted: Boolean): CatalogEntry? {
        json ?: return null
        val id = (json.opt("id") as? String)?.trim()
        if (!ManifestParser.validId(id)) return null
        val kind = CatalogEntryKind.fromWire(json.opt("kind") as? String ?: "remote") ?: return null
        val name = ExtensionUrlPolicy.text(json.opt("name") as? String, 80) ?: return null
        val manifestUrl = if (kind == CatalogEntryKind.REMOTE) {
            val raw = json.opt("manifestUrl") as? String ?: return null
            val resolved = source.resolve(raw.trim()) ?: return null
            ExtensionUrlPolicy.validateManifestUrl(resolved.toString())?.toString() ?: return null
        } else null
        val inline = if (trusted && kind == CatalogEntryKind.REMOTE) (json.opt("manifest") as? JSONObject)?.toString() else null
        return CatalogEntry(
            id = id!!,
            kind = kind,
            name = name,
            description = ExtensionUrlPolicy.text(json.opt("description") as? String, 300).orEmpty(),
            author = ExtensionUrlPolicy.text(json.opt("author") as? String, 80) ?: "Unknown",
            version = ExtensionUrlPolicy.text(json.opt("version") as? String, 32).orEmpty(),
            type = ExtensionType.fromWire(json.opt("type") as? String),
            manifestUrl = manifestUrl,
            preinstall = trusted && json.optBoolean("preinstall", false),
            inlineManifest = inline,
            catalogUrl = source.toString(),
            catalogName = catalogName,
        )
    }

    /**
     * Merges catalogs into one list, keyed by id. Earlier catalogs win, except that a live copy of
     * a catalog replaces its bundled snapshot (same URL) entry for entry.
     */
    fun merge(bundled: PluginCatalog?, fetched: List<PluginCatalog>): List<CatalogEntry> {
        val out = LinkedHashMap<String, CatalogEntry>()
        val liveFirstParty = fetched.firstOrNull { it.url == bundled?.url }
        bundled?.entries?.forEach { entry ->
            out[entry.id] = liveFirstParty?.entries?.firstOrNull { it.id == entry.id }
                ?.copy(preinstall = entry.preinstall, inlineManifest = entry.inlineManifest)
                ?: entry
        }
        fetched.forEach { catalog -> catalog.entries.forEach { out.putIfAbsent(it.id, it) } }
        return out.values.toList()
    }
}

/**
 * Install state for built-in plugins plus first-run seeding markers. A missing record means
 * "installed", so plugins that existed before catalogs keep working after an app update.
 */
class PluginInstallState internal constructor(private val values: KeyValueStore) {

    fun isBuiltInInstalled(id: String): Boolean = values.getString(BUILTIN_PREFIX + id) != "0"

    fun setBuiltInInstalled(id: String, installed: Boolean) {
        check(values.putString(BUILTIN_PREFIX + id, if (installed) "1" else "0"))
        listeners.forEach { runCatching { it(id, installed) } }
    }

    internal fun wasSeeded(id: String): Boolean = values.getString(SEEDED_PREFIX + id) != null

    internal fun markSeeded(id: String) {
        values.putString(SEEDED_PREFIX + id, "1")
    }

    companion object {
        private const val BUILTIN_PREFIX = "builtin."
        private const val SEEDED_PREFIX = "seeded."

        /** Built-in plugin ids this build of the app can install/uninstall. Set by the host app. */
        @Volatile
        var supportedBuiltIns: Set<String> = emptySet()

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, Boolean) -> Unit>()

        /** Called on the thread that changes state (the UI thread in practice). */
        fun addListener(listener: (id: String, installed: Boolean) -> Unit) {
            listeners += listener
        }

        @Volatile
        private var instance: PluginInstallState? = null

        fun get(context: Context): PluginInstallState = instance ?: synchronized(this) {
            instance ?: PluginInstallState(SharedPrefsKeyValueStore(context, PREFS)).also { instance = it }
        }

        internal const val PREFS = "slatetv_plugin_catalog"
    }
}

/** Catalog URLs the user has added, after the always-present first-party catalog. */
internal class CatalogSourceStore(private val values: KeyValueStore) {

    fun list(): List<String> = listOf(DEFAULT_CATALOG_URL) + userSources()

    fun userSources(): List<String> {
        val raw = values.getString(KEY) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { ExtensionUrlPolicy.validateManifestUrl(array.optString(it))?.toString() }
            .filter { it != DEFAULT_CATALOG_URL }
            .distinct()
    }

    /** Returns the normalized URL that was added. */
    fun add(url: String): String {
        val parsed = ExtensionUrlPolicy.validateManifestUrl(url)?.toString()
            ?: throw IllegalArgumentException("Enter an https:// address")
        val current = userSources()
        if (parsed == DEFAULT_CATALOG_URL || parsed in current) return parsed
        require(current.size < MAX_SOURCES) { "Too many plugin sources" }
        save(current + parsed)
        return parsed
    }

    fun remove(url: String) = save(userSources() - url)

    private fun save(list: List<String>) {
        check(values.putString(KEY, JSONArray(list).toString()))
    }

    companion object {
        const val MAX_SOURCES = 8
        private const val KEY = "sources"

        fun create(context: Context) = CatalogSourceStore(SharedPrefsKeyValueStore(context, PluginInstallState.PREFS))
    }
}

/** The first-party catalog, published from the SlateTV plugins repository via GitHub Pages. */
const val DEFAULT_CATALOG_URL = "https://elevate08.github.io/slatetv-plugins/catalog.json"

/** Bundled snapshot of the first-party catalog plus first-run seeding of its pre-installed plugins. */
object BundledPlugins {
    internal const val ASSET = "slatetv/bundled_catalog.json"

    internal fun load(context: Context): PluginCatalog? = runCatching {
        val body = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        CatalogParser.parse(body, ExtensionUrlPolicy.parseHttpUrl(DEFAULT_CATALOG_URL)!!, trusted = true)
    }.getOrNull()

    /** Installs bundled `preinstall` plugins once each. Safe to call on every launch. */
    fun seed(context: Context) {
        val catalog = load(context) ?: return
        seed(catalog, ExtensionStore.create(context), PluginInstallState.get(context))
    }

    /**
     * Each plugin is seeded at most once per device: the seeded marker is the tombstone that
     * keeps a plugin the user uninstalled from coming back on the next launch or app update.
     */
    internal fun seed(catalog: PluginCatalog, store: ExtensionStore, state: PluginInstallState) {
        for (entry in catalog.entries) {
            if (!entry.preinstall || entry.kind != CatalogEntryKind.REMOTE || state.wasSeeded(entry.id)) continue
            val inline = entry.inlineManifest ?: continue
            val url = entry.manifestUrl?.let(ExtensionUrlPolicy::parseHttpUrl) ?: continue
            runCatching {
                val manifest = ManifestParser.parse(inline, url)
                if (manifest.id == entry.id && store.get(entry.id) == null) {
                    store.upsert(InstalledExtension(manifest, enabled = true, installedAtEpochMs = System.currentTimeMillis()))
                }
            }
            state.markSeeded(entry.id)
        }
    }
}
