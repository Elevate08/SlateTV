package com.stasisstate.slatetv.plugin.jellyfin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CardTapAction { OPEN_GUIDE, LAUNCH_JELLYFIN }

data class JellyfinConfig(
    val serverUrl: String = "",
    val apiKey: String = "",
    val userId: String = "",
    val maxHomeChannels: Int = 12,
    val favoritesOnly: Boolean = false,
    val cardTapAction: CardTapAction = CardTapAction.OPEN_GUIDE,
    val allowUntrustedTls: Boolean = false,
) {
    override fun toString() = "JellyfinConfig(maxHomeChannels=$maxHomeChannels, favoritesOnly=$favoritesOnly, cardTapAction=$cardTapAction)"
}

internal fun parseServerUrl(value: String) = value.trim().takeIf { it.length <= 1500 }
    ?.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }

internal fun validApiKey(value: String) =
    value.length in 1..512 && value.all { it in '!'..'~' && it !in "\"\\," }

class JellyfinConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)

    fun load(): JellyfinConfig = synchronized(lock) {
        JellyfinConfig(
            prefs.getString("server", "").orEmpty(),
            runCatching { decrypt(prefs.getString("encryptedKey", "").orEmpty()) }.getOrDefault(""),
            prefs.getString("user", "").orEmpty(),
            prefs.getInt("limit", 12),
            prefs.getBoolean("favorites", false),
            if (prefs.getBoolean("launchApp", false)) CardTapAction.LAUNCH_JELLYFIN else CardTapAction.OPEN_GUIDE,
            prefs.getBoolean("lanTls", false),
        )
    }

    fun save(config: JellyfinConfig) = synchronized(lock) {
        val server = requireNotNull(parseServerUrl(config.serverUrl))
        require(validApiKey(config.apiKey))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(config.apiKey.toByteArray()))
        check(prefs.edit()
            .putString("server", server.toString())
            .putString("encryptedKey", encrypted)
            .putString("user", config.userId)
            .putInt("limit", config.maxHomeChannels)
            .putBoolean("favorites", config.favoritesOnly)
            .putBoolean("launchApp", config.cardTapAction == CardTapAction.LAUNCH_JELLYFIN)
            .putBoolean("lanTls", config.allowUntrustedTls)
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
        (store.getKey("jellyfin_api_key", null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("jellyfin_api_key",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { val lock = Any() }
}
