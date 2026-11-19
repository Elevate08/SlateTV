package com.stasisstate.slatetv.connector.livetv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CardTapAction { OPEN_GUIDE, LAUNCH_JELLYFIN }

/** Where Live TV channels come from: the Android TV system EPG (no login) or a Jellyfin server. */
enum class LiveTvSourceMode { SYSTEM_LIVE_CHANNELS, JELLYFIN_SERVER }

/**
 * A server certificate the user explicitly approved (trust on first use), bound to one `scheme://host:port`
 * origin. Only this exact leaf certificate is trusted for that origin.
 */
class TofuPin(val origin: String, val certDer: ByteArray) {
    val fingerprint: String get() = LiveTvTofuTls.sha256Fingerprint(certDer)
    override fun equals(other: Any?) = other is TofuPin && origin == other.origin && certDer.contentEquals(other.certDer)
    override fun hashCode() = 31 * origin.hashCode() + certDer.contentHashCode()
    override fun toString() = "TofuPin(origin=$origin)"
}

data class LiveTvConfig(
    val serverUrl: String = "",
    val apiKey: String = "",
    val userId: String = "",
    val maxHomeChannels: Int = 12,
    val favoritesOnly: Boolean = false,
    val cardTapAction: CardTapAction = CardTapAction.OPEN_GUIDE,
    /** Base64 DER of the user-approved leaf certificate, or empty. A String so config equality tracks pin changes. */
    val pinnedCertDer: String = "",
    /** Origin ([serverOrigin]) the certificate was approved for. */
    val pinnedOrigin: String = "",
    val sourceMode: LiveTvSourceMode = LiveTvSourceMode.SYSTEM_LIVE_CHANNELS,
) {
    /** The pin that applies to the currently configured server, or `null` if none / it belongs to another origin. */
    fun activePin(): TofuPin? {
        if (pinnedCertDer.isEmpty() || pinnedOrigin.isEmpty()) return null
        val base = parseServerUrl(serverUrl) ?: return null
        if (!base.isHttps || serverOrigin(base) != pinnedOrigin) return null
        val der = runCatching { Base64.getDecoder().decode(pinnedCertDer) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return TofuPin(pinnedOrigin, der)
    }

    fun withPin(pin: TofuPin?): LiveTvConfig = copy(
        pinnedCertDer = pin?.let { Base64.getEncoder().encodeToString(it.certDer) }.orEmpty(),
        pinnedOrigin = pin?.origin.orEmpty(),
    )

    /** Drops a pin that no longer matches the configured server origin. */
    fun normalized(): LiveTvConfig = if (pinnedCertDer.isEmpty() || activePin() != null) this else withPin(null)

    override fun toString() = "LiveTvConfig(sourceMode=$sourceMode, maxHomeChannels=$maxHomeChannels, favoritesOnly=$favoritesOnly, cardTapAction=$cardTapAction)"
}

internal fun serverOrigin(base: HttpUrl) = "${base.scheme}://${base.host}:${base.port}"

/** Mode used when nothing was saved: existing configured installs keep Jellyfin, new installs use the system EPG. */
internal fun defaultSourceMode(savedMode: String?, savedServer: String): LiveTvSourceMode =
    savedMode?.let { runCatching { LiveTvSourceMode.valueOf(it) }.getOrNull() }
        ?: if (savedServer.isNotBlank()) LiveTvSourceMode.JELLYFIN_SERVER else LiveTvSourceMode.SYSTEM_LIVE_CHANNELS

internal fun parseServerUrl(value: String) = value.trim().takeIf { it.length <= 1500 }
    ?.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }

internal fun validApiKey(value: String) =
    value.length in 1..512 && value.all { it in '!'..'~' && it !in "\"\\," }

class LiveTvConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): LiveTvConfig = synchronized(lock) {
        val server = prefs.getString("server", "").orEmpty()
        LiveTvConfig(
            serverUrl = server,
            apiKey = runCatching { decrypt(prefs.getString("encryptedKey", "").orEmpty()) }.getOrDefault(""),
            userId = prefs.getString("user", "").orEmpty(),
            maxHomeChannels = prefs.getInt("limit", 12),
            favoritesOnly = prefs.getBoolean("favorites", false),
            cardTapAction = if (prefs.getBoolean("launchApp", false)) CardTapAction.LAUNCH_JELLYFIN else CardTapAction.OPEN_GUIDE,
            pinnedCertDer = prefs.getString("pinCert", "").orEmpty(),
            pinnedOrigin = prefs.getString("pinOrigin", "").orEmpty(),
            sourceMode = defaultSourceMode(prefs.getString("sourceMode", null), server),
        ).normalized()
    }

    /**
     * Persists [config]. A blank server URL / API key is allowed (System Live Channels needs neither);
     * a non-blank value must be valid.
     */
    fun save(config: LiveTvConfig) = synchronized(lock) {
        val server = config.serverUrl.trim().takeIf { it.isNotEmpty() }?.let { requireNotNull(parseServerUrl(it)) }
        require(config.apiKey.isEmpty() || validApiKey(config.apiKey))
        val toSave = config.copy(serverUrl = server?.toString().orEmpty()).normalized()
        val encrypted = if (toSave.apiKey.isEmpty()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(toSave.apiKey.toByteArray()))
        }
        check(prefs.edit()
            .putString("server", toSave.serverUrl)
            .putString("encryptedKey", encrypted)
            .putString("user", toSave.userId)
            .putInt("limit", toSave.maxHomeChannels)
            .putBoolean("favorites", toSave.favoritesOnly)
            .putBoolean("launchApp", toSave.cardTapAction == CardTapAction.LAUNCH_JELLYFIN)
            .putString("pinCert", toSave.pinnedCertDer)
            .putString("pinOrigin", toSave.pinnedOrigin)
            .putString("sourceMode", toSave.sourceMode.name)
            .commit())
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object {
        const val PREFS = "slatetv_connector_livetv"
        const val KEY_ALIAS = "slatetv_connector_livetv_v1"
        val lock = Any()
    }
}
