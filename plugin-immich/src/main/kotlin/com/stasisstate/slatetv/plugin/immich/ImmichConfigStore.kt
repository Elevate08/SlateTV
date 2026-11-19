package com.stasisstate.slatetv.plugin.immich

import android.content.Context
import android.content.SharedPreferences

interface ImmichConfigStore {
    fun load(): ImmichConfig
    fun save(config: ImmichConfig)
}

class SharedPrefsImmichConfigStore(context: Context) : ImmichConfigStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ImmichConfig {
        val modeName = prefs.getString(KEY_SOURCE_MODE, ImmichSourceMode.RANDOM_LIBRARY.name)
        val sourceMode = runCatching {
            ImmichSourceMode.valueOf(modeName ?: ImmichSourceMode.RANDOM_LIBRARY.name)
        }.getOrDefault(ImmichSourceMode.RANDOM_LIBRARY)

        val qualityName = prefs.getString(KEY_IMAGE_QUALITY, ImmichImageQuality.PREVIEW.name)
        val quality = runCatching {
            ImmichImageQuality.valueOf(qualityName ?: ImmichImageQuality.PREVIEW.name)
        }.getOrDefault(ImmichImageQuality.PREVIEW)

        val storedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        return ImmichConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            apiKey = KeystoreSecretCipher.decrypt(storedKey),
            sourceMode = sourceMode,
            selectedAlbumIds = prefs.getStringSet(KEY_ALBUM_IDS, emptySet())?.toSet() ?: emptySet(),
            imageQuality = quality,
            landscapeOnly = prefs.getBoolean(KEY_LANDSCAPE_ONLY, true),
            allowUntrustedTls = prefs.getBoolean(KEY_ALLOW_UNTRUSTED_TLS, false),
        )
    }

    override fun save(config: ImmichConfig) {
        val encryptedKey = KeystoreSecretCipher.encrypt(config.apiKey.trim())
        prefs.edit()
            .putString(KEY_SERVER_URL, config.normalizedServerUrl)
            .putString(KEY_API_KEY, encryptedKey)
            .putString(KEY_SOURCE_MODE, config.sourceMode.name)
            .putStringSet(KEY_ALBUM_IDS, config.selectedAlbumIds)
            .putString(KEY_IMAGE_QUALITY, config.imageQuality.name)
            .putBoolean(KEY_LANDSCAPE_ONLY, config.landscapeOnly)
            .putBoolean(KEY_ALLOW_UNTRUSTED_TLS, config.allowUntrustedTls)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "slate_immich_plugin_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_ALBUM_IDS = "album_ids"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_LANDSCAPE_ONLY = "landscape_only"
        private const val KEY_ALLOW_UNTRUSTED_TLS = "allow_untrusted_tls"
    }
}
