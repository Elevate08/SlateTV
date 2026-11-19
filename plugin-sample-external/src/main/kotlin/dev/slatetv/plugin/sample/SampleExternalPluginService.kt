package dev.slatetv.plugin.sample

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import dev.slatetv.plugin.api.ISlateBackgroundPlugin
import dev.slatetv.plugin.api.PluginContract
import dev.slatetv.plugin.api.PluginInfo
import dev.slatetv.plugin.api.PluginMediaItem
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference external APK background plugin demonstrating Slate TV's AIDL contract.
 *
 * Returns `ambient://` palette URIs (or could return authenticated HTTP URLs
 * with `headers` Bundle entries, or `content://` FileProvider URIs).
 */
class SampleExternalPluginService : Service() {

    private val cursor = AtomicInteger(0)

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
            val idx = Math.floorMod(cursor.getAndIncrement(), palettes.size)
            val (id, uri, subtitle) = palettes[idx]
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
            return MutableList(n) { getNextMedia(options) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
