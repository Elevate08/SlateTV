package dev.slatetv.plugin.api

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Intent action and manifest metadata constants for external Slate TV plugins.
 */
object PluginContract {
    const val API_VERSION = 1

    /** Intent action that external plugin Services must declare in AndroidManifest.xml. */
    const val ACTION_BACKGROUND_PROVIDER = "dev.slatetv.launcher.plugin.BACKGROUND_PROVIDER"

    /** Optional manifest meta-data keys on the plugin Service. */
    const val META_PLUGIN_ID = "dev.slatetv.plugin.ID"
    const val META_PLUGIN_NAME = "dev.slatetv.plugin.NAME"
    const val META_PLUGIN_DESCRIPTION = "dev.slatetv.plugin.DESCRIPTION"
    const val META_SETTINGS_ACTIVITY = "dev.slatetv.plugin.SETTINGS_ACTIVITY"
    const val META_API_VERSION = "dev.slatetv.plugin.API_VERSION"

    /** Option bundle keys passed into getNextMedia / getMediaBatch. */
    const val OPT_TARGET_WIDTH = "target_width"
    const val OPT_TARGET_HEIGHT = "target_height"
    const val OPT_PREFER_LANDSCAPE = "prefer_landscape"
    const val OPT_LAST_MEDIA_ID = "last_media_id"
}

/**
 * Parcelable plugin metadata transmitted over AIDL.
 */
@Parcelize
data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val supportsNext: Boolean = true,
    val settingsActivityClassName: String? = null,
) : Parcelable

/**
 * Parcelable media descriptor returned by external AIDL plugins.
 */
@Parcelize
data class PluginMediaItem(
    val id: String,
    val imageUri: String,
    val headers: Bundle = Bundle(),
    val title: String? = null,
    val subtitle: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val allowUntrustedTls: Boolean = false,
) : Parcelable {
    fun toBackgroundMedia(): BackgroundMedia {
        val headerMap = buildMap<String, String> {
            for (key in headers.keySet()) {
                headers.getString(key)?.let { put(key, it) }
            }
        }
        return BackgroundMedia(
            id = id,
            imageUri = imageUri,
            headers = headerMap,
            title = title,
            subtitle = subtitle,
            width = width,
            height = height,
            allowUntrustedTls = allowUntrustedTls,
        )
    }

    companion object {
        fun from(media: BackgroundMedia): PluginMediaItem {
            val bundle = Bundle().apply {
                media.headers.forEach { (k, v) -> putString(k, v) }
            }
            return PluginMediaItem(
                id = media.id,
                imageUri = media.imageUri,
                headers = bundle,
                title = media.title,
                subtitle = media.subtitle,
                width = media.width,
                height = media.height,
                allowUntrustedTls = media.allowUntrustedTls,
            )
        }
    }
}
