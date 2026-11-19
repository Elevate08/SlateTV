package com.stasisstate.slatetv.connector.immich

import android.content.Context
import android.content.SharedPreferences

interface ImmichConfigStore {
    fun load(): ImmichConfig
    fun save(config: ImmichConfig)
}

/** Private, app-sandboxed prefs (`slatetv_connector_immich`); the API key is Keystore-encrypted at rest. */
class SharedPrefsImmichConfigStore(context: Context) : ImmichConfigStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ImmichConfig {
        val sourceMode = runCatching {
            ImmichSourceMode.valueOf(prefs.getString(KEY_SOURCE_MODE, null) ?: ImmichSourceMode.RANDOM_LIBRARY.name)
        }.getOrDefault(ImmichSourceMode.RANDOM_LIBRARY)
        val quality = runCatching {
            ImmichImageQuality.valueOf(prefs.getString(KEY_IMAGE_QUALITY, null) ?: ImmichImageQuality.PREVIEW.name)
        }.getOrDefault(ImmichImageQuality.PREVIEW)
        val pinOrigin = prefs.getString(KEY_PIN_ORIGIN, "").orEmpty()
        val pinDer = prefs.getString(KEY_PIN_DER, "").orEmpty()
        return ImmichConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
            apiKey = KeystoreSecretCipher.decrypt(prefs.getString(KEY_API_KEY, "").orEmpty()),
            sourceMode = sourceMode,
            selectedAlbumIds = prefs.getStringSet(KEY_ALBUM_IDS, emptySet())?.toSet().orEmpty(),
            imageQuality = quality,
            landscapeOnly = prefs.getBoolean(KEY_LANDSCAPE_ONLY, true),
            pinnedCert = if (pinOrigin.isNotBlank() && pinDer.isNotBlank()) PinnedCert(pinOrigin, pinDer) else null,
        )
    }

    override fun save(config: ImmichConfig) {
        val pin = config.pinnedCert
        prefs.edit()
            .putString(KEY_SERVER_URL, config.normalizedServerUrl)
            .putString(KEY_API_KEY, KeystoreSecretCipher.encrypt(config.apiKey.trim()))
            .putString(KEY_SOURCE_MODE, config.sourceMode.name)
            .putStringSet(KEY_ALBUM_IDS, config.selectedAlbumIds)
            .putString(KEY_IMAGE_QUALITY, config.imageQuality.name)
            .putBoolean(KEY_LANDSCAPE_ONLY, config.landscapeOnly)
            .putString(KEY_PIN_ORIGIN, pin?.origin.orEmpty())
            .putString(KEY_PIN_DER, pin?.derBase64.orEmpty())
            .apply()
    }

    companion object {
        const val PREFS_NAME = "slatetv_connector_immich"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_ALBUM_IDS = "album_ids"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_LANDSCAPE_ONLY = "landscape_only"
        private const val KEY_PIN_ORIGIN = "pinned_cert_origin"
        private const val KEY_PIN_DER = "pinned_cert_der"
    }
}
