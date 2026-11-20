package com.stasisstate.slatetv.connector.livetv

import android.content.Context
import android.net.Uri
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_APP_CHANNEL_PACKAGE
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_PREVIEW_PROGRAM_ID
import com.stasisstate.slatetv.connector.livetv.BuiltInLiveTvShelfPlugin.Companion.EXTRA_WATCH_NEXT_PROGRAM_ID
import com.stasisstate.slatetv.plugin.api.ShelfCard
import com.stasisstate.slatetv.plugin.api.ShelfData
import com.stasisstate.slatetv.plugin.api.ShelfPlugin
import com.stasisstate.slatetv.plugin.api.ShelfPluginMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/** One Home shelf per enabled preview channel, plus an optional Watch Next shelf. */
class BuiltInAppChannelsProvider internal constructor(
    private val hostPackage: String,
    private val source: AndroidTvAppChannelsSource,
    private val loadConfig: () -> AppChannelsConfig,
    private val hasPermission: () -> Boolean,
    private val artworkFor: suspend (AppProgramRow) -> String?,
) {
    constructor(context: Context) : this(
        hostPackage = context.packageName,
        source = androidTvAppChannelsSource(context.applicationContext),
        loadConfig = { AppChannelsConfigStore(context.applicationContext).load() },
        hasPermission = { hasTvListingsPermission(context.applicationContext) },
        artworkFor = appChannelArtwork(context.applicationContext)::load,
    )

    /** Called off the main thread by the shelf registry. */
    fun shelfPlugins(): List<ShelfPlugin> {
        if (!hasPermission()) return emptyList()
        val discovered = source.discover()
        val config = loadConfig()
        val watchNext = AppChannel.WatchNext
        // The default uses Watch Next only when it has items. Reuse that query for the shelf.
        val watchPrograms = if (config.hasUserCustomized) null
        else runCatching { source.programs(watchNext) }.getOrNull()
        val enabled = config.enabledKeys(discovered, watchPrograms?.items?.isNotEmpty() == true)
        return discovered.filter { it.key in enabled }.map { channel ->
            AppChannelShelf(channel, if (channel == watchNext) watchPrograms else null)
        }
    }

    private inner class AppChannelShelf(
        private val channel: AppChannel,
        private val watchPrograms: AppChannelPrograms?,
    ) : ShelfPlugin {
        override val metadata = ShelfPluginMetadata(
            id = "$PLUGIN_ID_PREFIX${channel.key}",
            name = channel.shelfTitle,
            description = channel.shelfTitle,
            version = "1.0.0",
            packageName = hostPackage,
        )

        override suspend fun fetchShelf(): ShelfData? = withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext ShelfData(false, metadata.name, emptyList())
            val programs = watchPrograms ?: source.programs(channel)
            // Keep whatever artwork resolves within the budget instead of dropping all of it on timeout.
            val resolvedArtwork = java.util.concurrent.ConcurrentHashMap<Long, String>()
            withTimeoutOrNull(APP_CHANNEL_SHELF_ARTWORK_BUDGET_MS) {
                programs.items.map { program ->
                    async { artworkFor(program)?.let { resolvedArtwork[program.id] = it } }
                }.awaitAll()
            }
            val cards = programs.items.map { program ->
                val artwork = resolvedArtwork[program.id] ?: fallbackWebArtwork(program)
                appProgramCard(channel, program, artwork)
            }
            ShelfData(cards.isNotEmpty(), metadata.name, cards)
        }
    }

    companion object {
        const val PLUGIN_ID_PREFIX = "appchannel:"
    }
}

internal class AppChannelArtwork(
    cache: () -> LiveTvLogoCache,
    private val readContent: suspend (uri: String, ownerPackage: String) -> ByteArray?,
) {
    private val logoCache by lazy(cache)
    private val permits = Semaphore(LOGO_FETCH_PERMITS)

    suspend fun load(program: AppProgramRow): String? {
        val candidates = listOfNotNull(program.posterUri, program.thumbnailUri).mapNotNull { raw ->
            val uri = raw.trim().takeIf { it.length in 1..2048 && it.none(Char::isISOControl) } ?: return@mapNotNull null
            uri.takeIf { it.startsWith("content://") }?.let(::downsizedJellyfinArtworkUri) ?: appChannelWebArtwork(uri)
        }.distinct()
        val contentUris = candidates.takeWhile { it.startsWith("content://") }
        val webFallback = candidates.getOrNull(contentUris.size)
        if (contentUris.isEmpty()) return webFallback

        val day = (System.currentTimeMillis() / 86_400_000L).toString()
        return permits.withPermit {
            withTimeoutOrNull(LOGO_FETCH_TIMEOUT_MS) {
                contentUris.firstNotNullOfOrNull { uri ->
                    logoCache.publishCached(uri, day, program.ownerPackage) { readContent(uri, program.ownerPackage) }
                }
            }
        } ?: webFallback
    }
}

private const val APP_CHANNEL_SHELF_ARTWORK_BUDGET_MS = 1_500L

private fun fallbackWebArtwork(program: AppProgramRow): String? =
    listOfNotNull(program.posterUri, program.thumbnailUri).firstNotNullOfOrNull { raw ->
        raw.trim().takeIf { it.length in 1..2048 && it.none(Char::isISOControl) }?.let(::appChannelWebArtwork)
    }

private fun appChannelArtwork(app: Context): AppChannelArtwork {
    // A detached, bounded pool lets the 2-second deadline return; closing the AssetFileDescriptor in finally
    // unblocks any stalled provider read immediately so pool threads are never permanently wedged.
    val artworkIo = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(LOGO_FETCH_PERMITS))
    val cache = { LiveTvLogoCache(File(app.cacheDir, "connector_app_channel_artwork"), maxFiles = 512, maxBytes = 96L * 1024 * 1024) }
    return AppChannelArtwork(cache) { raw, ownerPackage ->
        val uri = Uri.parse(raw)
        val authority = uri.encodedAuthority?.takeUnless { '@' in it } ?: return@AppChannelArtwork null
        // Only open artwork served by the publishing app in the current Android user.
        @Suppress("DEPRECATION")
        val provider = runCatching { app.packageManager.resolveContentProvider(authority, 0) }.getOrNull()
        if (provider?.packageName != ownerPackage) return@AppChannelArtwork null
        val afd = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
            ?: return@AppChannelArtwork null
        val read = artworkIo.async {
            runInterruptible {
                afd.use { descriptor ->
                    descriptor.createInputStream().use { input -> readCappedStreamBytes(input) }
                }
            }
        }
        try {
            read.await()
        } finally {
            read.cancel()
            runCatching { afd.close() }
        }
    }
}

private val IPV4_LITERAL = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")

internal fun appChannelWebArtwork(raw: String): String? =
    raw.toHttpUrlOrNull()
        ?.takeIf { url ->
            url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                !url.host.equals("localhost", ignoreCase = true) && !url.host.endsWith(".localhost", ignoreCase = true) &&
                (!IPV4_LITERAL.matches(url.host) && ':' !in url.host ||
                    runCatching { java.net.InetAddress.getByName(url.host).isPublicInternetAddress() }.getOrDefault(false))
        }
        ?.toString()

internal fun appProgramCard(channel: AppChannel, row: AppProgramRow, artwork: String?): ShelfCard {
    val idExtra = when (channel) {
        is AppChannel.Preview -> EXTRA_PREVIEW_PROGRAM_ID
        AppChannel.WatchNext -> EXTRA_WATCH_NEXT_PROGRAM_ID
    }
    val title = cleanAppChannelText(row.title, 120)
    val episode = cleanAppChannelText(row.episodeTitle, 120)
    val duration = row.durationMs ?: 0
    val position = row.playbackPositionMs ?: 0
    return ShelfCard(
        id = "${channel.key}:${row.id}",
        title = title ?: episode ?: "Untitled program",
        subtitle = episode?.takeIf { title != null && episode != title } ?: cleanAppChannelText(row.description, 160),
        badge = if (row.live) "LIVE" else null,
        imageUri = artwork,
        progressPercent = if (duration > 0 && position > 0) ((position.toDouble() / duration) * 100).toInt().coerceIn(0, 100) else -1,
        launchPackageName = row.ownerPackage,
        launchExtras = mapOf(idExtra to row.id, EXTRA_APP_CHANNEL_PACKAGE to row.ownerPackage),
    )
}

internal const val JELLYFIN_IMAGE_PROVIDER_AUTHORITY = "org.jellyfin.androidtv.integration.provider.ImageProvider"
private const val JELLYFIN_ARTWORK_MAX_WIDTH = "400"

/**
 * Jellyfin for Android TV publishes full-size posters through its ImageProvider (`?src=<server image URL>`), ~3 MB and
 * ~3 s each, which exceeds the artwork size cap and budget. Its server image API accepts `maxWidth`/`quality`, so ask the
 * same provider for the same server image at card size (~120 KB, ~0.1–0.5 s). Any other URI is returned unchanged.
 */
internal fun downsizedJellyfinArtworkUri(raw: String): String {
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return raw
    if (uri.scheme != "content" || uri.rawAuthority != JELLYFIN_IMAGE_PROVIDER_AUTHORITY) return raw
    val params = uri.rawQuery?.split('&').orEmpty()
    val srcIndex = params.indexOfFirst { it.startsWith("src=") }.takeIf { it >= 0 } ?: return raw
    val src = runCatching { java.net.URLDecoder.decode(params[srcIndex].removePrefix("src="), "UTF-8") }.getOrNull()
        ?.toHttpUrlOrNull() ?: return raw
    if (!src.encodedPath.contains("/Items/") || !src.encodedPath.contains("/Images/")) return raw
    if (src.queryParameter("maxWidth") != null || src.queryParameter("fillWidth") != null) return raw
    val sized = src.newBuilder()
        .addQueryParameter("maxWidth", JELLYFIN_ARTWORK_MAX_WIDTH)
        .addQueryParameter("quality", "80")
        .build().toString()
    val rebuilt = params.toMutableList().apply { this[srcIndex] = "src=" + java.net.URLEncoder.encode(sized, "UTF-8") }
    return "content://$JELLYFIN_IMAGE_PROVIDER_AUTHORITY?" + rebuilt.joinToString("&")
}
