package dev.slatetv.launcher.wallpaper.plugins

import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.BackgroundPlugin
import dev.slatetv.plugin.api.PluginMetadata
import dev.slatetv.plugin.api.PluginRequestContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-network curated dark ambient gradients.
 * Serves both as a serene default plugin and as the instant fallback whenever
 * a network plugin is unconfigured or unreachable — guaranteeing no black screen.
 */
class AmbientSlatePlugin : BackgroundPlugin {

    private val index = AtomicInteger(0)

    override val metadata: PluginMetadata = PluginMetadata(
        id = PLUGIN_ID,
        name = "Ambient Slate",
        description = "Minimalist dark architectural gradients (instant, offline)",
        version = "1.0.0",
        isExternal = false,
        supportsNext = true,
    )

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia {
        val i = Math.floorMod(index.getAndIncrement(), PRESETS.size)
        val preset = PRESETS[i]
        return BackgroundMedia(
            id = "ambient:${preset.id}",
            imageUri = preset.uri,
            subtitle = null,
            width = request.targetWidth,
            height = request.targetHeight,
        )
    }

    private data class Preset(val id: String, val uri: String)

    companion object {
        const val PLUGIN_ID = "builtin.ambient"
        const val DEFAULT_URI = "ambient://#0D1520,#162436,#080B10"

        private val PRESETS = listOf(
            Preset("midnight", "ambient://#0D1520,#162436,#080B10"),
            Preset("obsidian", "ambient://#12131A,#1E2230,#090A0E"),
            Preset("evergreen", "ambient://#0B1715,#142B26,#070D0C"),
            Preset("indigo", "ambient://#111224,#1F2140,#080912"),
            Preset("graphite", "ambient://#17181B,#25282E,#0B0C0E"),
        )
    }
}
