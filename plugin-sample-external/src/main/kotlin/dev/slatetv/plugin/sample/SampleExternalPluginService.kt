package dev.slatetv.plugin.sample

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import dev.slatetv.plugin.api.ISlateBackgroundPlugin
import dev.slatetv.plugin.api.PluginContract
import dev.slatetv.plugin.api.PluginInfo
import dev.slatetv.plugin.api.PluginMediaItem

/**
 * Reference external APK background plugin demonstrating Slate TV's AIDL contract.
 *
 * Advances deterministically from [PluginContract.OPT_LAST_MEDIA_ID] so rotation
 * remains predictable even though Slate TV unbinds the service between fetches.
 */
class SampleExternalPluginService : Service() {

    private val palettes = listOf(
        Triple("nordic-fjord", "ambient://#0B1622,#132A3E,#080D14", "Nordic Fjord"),
        Triple("nordic-pine", "ambient://#0A1916,#122E27,#070E0D", "Boreal Pine"),
        Triple("nordic-dusk", "ambient://#171324,#281D3B,#0B0A12", "Arctic Dusk"),
        Triple("nordic-ember", "ambient://#1D1411,#311F19,#0D0A09", "Hearth Slate"),
    )

    private val binder = object : ISlateBackgroundPlugin.Stub() {
        override fun getApiVersion(): Int = PluginContract.API_VERSION

        override fun getPluginInfo(): PluginInfo = PluginInfo(
            id = "external.sample.nordic",
            name = "Nordic Palettes (Sample Plugin)",
            description = "Reference external AIDL plugin for Slate TV",
            version = "1.0.0",
            supportsNext = true,
            settingsActivityClassName = SampleSettingsActivity::class.java.name,
        )

        override fun getNextMedia(options: Bundle?): PluginMediaItem {
            val lastId = options?.getString(PluginContract.OPT_LAST_MEDIA_ID)
            val prevIdx = palettes.indexOfFirst { it.first == lastId }
            val nextIdx = if (prevIdx == -1) 0 else (prevIdx + 1) % palettes.size
            val (id, uri, subtitle) = palettes[nextIdx]
            return PluginMediaItem(
                id = id,
                imageUri = uri,
                title = null,
                subtitle = subtitle,
                width = 1920,
                height = 1080,
            )
        }

        override fun getMediaBatch(count: Int, options: Bundle?): MutableList<PluginMediaItem> {
            val n = count.coerceIn(1, palettes.size)
            val lastId = options?.getString(PluginContract.OPT_LAST_MEDIA_ID)
            val prevIdx = palettes.indexOfFirst { it.first == lastId }
            val startIdx = if (prevIdx == -1) 0 else (prevIdx + 1) % palettes.size
            return MutableList(n) { offset ->
                val (id, uri, subtitle) = palettes[(startIdx + offset) % palettes.size]
                PluginMediaItem(
                    id = id,
                    imageUri = uri,
                    title = null,
                    subtitle = subtitle,
                    width = 1920,
                    height = 1080,
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
