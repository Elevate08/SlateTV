package com.stasisstate.slatetv.connector.extensions

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

internal class SharedPrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).commit()
    override fun remove(key: String) = prefs.edit().remove(key).commit()
}

/** Holds per-extension secrets (API keys / tokens). */
internal interface SecretVault {
    fun has(id: String): Boolean
    fun get(id: String): String?
    fun put(id: String, secret: String)
    fun remove(id: String)
}

/** AES-256-GCM secrets under an Android Keystore key; ciphertext is bound to the extension id. */
internal class KeystoreSecretVault(private val blobs: KeyValueStore) : SecretVault {
    override fun has(id: String) = blobs.getString(BLOB_PREFIX + id) != null

    override fun get(id: String): String? = synchronized(lock) {
        val stored = blobs.getString(BLOB_PREFIX + id) ?: return null
        runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            cipher.updateAAD(id.toByteArray())
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
        }.getOrNull()
    }

    override fun put(id: String, secret: String): Unit = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(id.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(secret.toByteArray())
        check(blobs.putString(BLOB_PREFIX + id, Base64.getEncoder().encodeToString(encrypted)))
    }

    override fun remove(id: String) {
        synchronized(lock) { blobs.remove(BLOB_PREFIX + id) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Missing keystore key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "slatetv_connector_extensions_v1"
        private const val BLOB_PREFIX = "key."
        private const val IV_BYTES = 12
        private val lock = Any()
    }
}

/** JSON (de)serialisation of installed extensions. Decoding re-validates everything it reads. */
internal object ExtensionStoreCodec {
    private const val VERSION = 1

    fun encode(extensions: List<InstalledExtension>): String = JSONObject()
        .put("v", VERSION)
        .put("extensions", JSONArray().also { array ->
            extensions.forEach { ext ->
                val m = ext.manifest
                array.put(
                    JSONObject()
                        .put("enabled", ext.enabled)
                        .put("installedAt", ext.installedAtEpochMs)
                        .put("manifest", JSONObject()
                            .put("id", m.id)
                            .put("name", m.name)
                            .put("description", m.description)
                            .put("version", m.version)
                            .put("author", m.author)
                            .put("type", m.type.wire)
                            .put("manifestUrl", m.manifestUrl)
                            .put("backgroundUrl", m.backgroundUrl ?: JSONObject.NULL)
                            .put("shelfUrl", m.shelfUrl ?: JSONObject.NULL)
                            .put("refreshIntervalSeconds", m.refreshIntervalSeconds)
                            .put("authHeaderName", m.authHeaderName ?: JSONObject.NULL)
                            .put("autoDetected", m.autoDetected)),
                )
            }
        })
        .toString()

    fun decode(json: String?): List<InstalledExtension> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONObject(json).optJSONArray("extensions") }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<InstalledExtension>()
        for (i in 0 until array.length()) {
            val ext = runCatching { decodeOne(array.getJSONObject(i)) }.getOrNull() ?: continue
            if (seen.add(ext.id)) out += ext
        }
        return out
    }

    private fun decodeOne(json: JSONObject): InstalledExtension? {
        val m = json.getJSONObject("manifest")
        val id = m.optString("id")
        if (!ManifestParser.validId(id)) return null
        val type = ExtensionType.fromWire(m.optString("type")) ?: return null
        val manifestUrl = ExtensionUrlPolicy.validateManifestUrl(m.optString("manifestUrl")) ?: return null
        fun endpoint(key: String): String? {
            if (m.isNull(key)) return null
            return ExtensionUrlPolicy.resolveEndpoint(manifestUrl, m.optString(key))?.toString()
                ?: throw IllegalArgumentException("bad endpoint")
        }
        val background = endpoint("backgroundUrl")
        val shelf = endpoint("shelfUrl")
        if (type.providesBackground && background == null) return null
        if (type.providesShelf && shelf == null) return null
        val authHeader = if (m.isNull("authHeaderName")) null else m.optString("authHeaderName")
        if (authHeader != null && !AuthHeader.validName(authHeader)) return null
        val manifest = ExtensionManifest(
            id = id,
            name = ExtensionUrlPolicy.text(m.optString("name"), 80) ?: return null,
            description = ExtensionUrlPolicy.text(m.optString("description"), 300).orEmpty(),
            version = ExtensionUrlPolicy.text(m.optString("version"), 32) ?: "1",
            author = ExtensionUrlPolicy.text(m.optString("author"), 80) ?: "Unknown",
            type = type,
            manifestUrl = manifestUrl.toString(),
            backgroundUrl = if (type.providesBackground) background else null,
            shelfUrl = if (type.providesShelf) shelf else null,
            refreshIntervalSeconds = ManifestParser.clampRefresh(m.optInt("refreshIntervalSeconds", ManifestParser.DEFAULT_REFRESH_SECONDS)),
            authHeaderName = authHeader,
            autoDetected = m.optBoolean("autoDetected", false),
        )
        return InstalledExtension(manifest, json.optBoolean("enabled", true), json.optLong("installedAt", 0L))
    }
}

/** Persistent list of installed extensions plus their encrypted API keys. */
internal class ExtensionStore(private val values: KeyValueStore, private val vault: SecretVault) {
    @Synchronized
    fun list(): List<InstalledExtension> =
        ExtensionStoreCodec.decode(values.getString(LIST_KEY)).map { it.copy(hasApiKey = vault.has(it.id)) }

    @Synchronized
    fun get(id: String): InstalledExtension? = list().firstOrNull { it.id == id }

    /** Inserts or replaces an extension. A non-null [apiKey] replaces the stored key; null keeps it. */
    @Synchronized
    fun upsert(extension: InstalledExtension, apiKey: String? = null) {
        require(apiKey == null || AuthHeader.validKey(apiKey)) { "Invalid API key" }
        val current = list().filterNot { it.id == extension.id }
        require(current.size < MAX_EXTENSIONS) { "Too many extensions installed" }
        // A stored key belongs to the origin it was saved for. Never let a manifest from another
        // origin inherit it by reusing an installed extension's id.
        val previous = list().firstOrNull { it.id == extension.id }
        if (apiKey == null && previous != null && !sameManifestOrigin(previous.manifest, extension.manifest)) {
            vault.remove(extension.id)
        }
        if (apiKey != null) vault.put(extension.id, apiKey)
        save(current + extension.copy(hasApiKey = false))
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filterNot { it.id == id })
        vault.remove(id)
    }

    /** Sets, or with null clears, the extension's API key. */
    @Synchronized
    fun setApiKey(id: String, apiKey: String?) {
        if (apiKey == null) {
            vault.remove(id)
        } else {
            require(AuthHeader.validKey(apiKey)) { "Invalid API key" }
            require(list().any { it.id == id }) { "Unknown extension" }
            vault.put(id, apiKey)
        }
    }

    fun apiKey(id: String): String? = vault.get(id)

    private fun sameManifestOrigin(a: ExtensionManifest, b: ExtensionManifest): Boolean {
        val ua = ExtensionUrlPolicy.parseHttpUrl(a.manifestUrl) ?: return false
        val ub = ExtensionUrlPolicy.parseHttpUrl(b.manifestUrl) ?: return false
        return ExtensionUrlPolicy.sameOrigin(ua, ub)
    }

    private fun save(extensions: List<InstalledExtension>) {
        check(values.putString(LIST_KEY, ExtensionStoreCodec.encode(extensions)))
    }

    companion object {
        const val MAX_EXTENSIONS = 32
        private const val LIST_KEY = "extensions"

        fun create(context: Context): ExtensionStore {
            val app = context.applicationContext
            return ExtensionStore(
                SharedPrefsKeyValueStore(app, "slatetv_connector_extensions"),
                KeystoreSecretVault(SharedPrefsKeyValueStore(app, "slatetv_connector_extensions_keys")),
            )
        }
    }
}
