package com.stasisstate.slatetv.plugin.jellyfin

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.stasisstate.slatetv.plugin.api.*
import kotlinx.coroutines.*
import java.time.Instant

internal const val JELLYFIN_TV = "org.jellyfin.androidtv"
internal const val CHANNEL_ID = "channel_id"

class JellyfinShelfPluginService : Service() {
    private val binder = object : ISlateShelfPlugin.Stub() {
        override fun getApiVersion() = PluginContract.API_VERSION

        override fun getShelfInfo() = ShelfPluginInfo(
            "com.stasisstate.slatetv.plugin.jellyfin.livetv", "Jellyfin Live TV",
            "Live TV channels and program guide from your Jellyfin server", "1.0.0",
            JellyfinSettingsActivity::class.java.name, JellyfinGuideActivity::class.java.name,
        )

        override fun getShelfPayload(options: Bundle?): ShelfPayload {
            if (Binder.getCallingUid() != Process.myUid()) {
                val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
                if ("com.stasisstate.slatetv" !in packages) return unavailable("Slate TV access required.")
            }
            return runCatching { runBlocking { shelf() } }
                .getOrElse { unavailable("Could not load Jellyfin Live TV.") }
        }
    }

    override fun onBind(intent: Intent?) = binder

    private suspend fun shelf(): ShelfPayload {
        val config = JellyfinConfigStore(this).load()
        val (refresh, cached) = synchronized(cacheLock) {
            if (snapshot != config) {
                pending?.cancel()
                pending = null
                lastPayload = null
                snapshot = config
            }
            if (config.serverUrl.isBlank() || config.apiKey.isBlank()) return unavailable("Configure Jellyfin first.")

            val age = SystemClock.elapsedRealtime() - completedAt
            val cached = lastPayload?.takeIf { age <= 90_000 }
            if (cached != null && age < 45_000) return cached

            val generation = snapshot
            val refresh = pending ?: refreshScope.async(start = CoroutineStart.LAZY) {
                val payload = try {
                    withTimeout(25_000) { fetchShelf(config) }
                } catch (_: TimeoutCancellationException) {
                    unavailable("Jellyfin took too long to respond.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unavailable("Could not load Jellyfin Live TV.")
                }
                synchronized(cacheLock) {
                    if (snapshot === generation) {
                        lastPayload = payload
                        completedAt = SystemClock.elapsedRealtime()
                        pending = null
                    }
                }
                payload
            }.also {
                pending = it
                it.start()
            }
            refresh to cached
        }
        return withTimeoutOrNull(3_300) { refresh.await() } ?: cached ?: unavailable("Loading Jellyfin Live TV.")
    }

    private suspend fun fetchShelf(config: JellyfinConfig): ShelfPayload {
        val api = JellyfinApiClient(config)
        if (!api.liveTvEnabled()) return unavailable("Live TV is unavailable on this server.")
        val user = config.userId.ifBlank { api.detectUserId() }
        val channels = api.channels(user, config.maxHomeChannels.coerceIn(1, 24), config.favoritesOnly)
        if (channels.isEmpty()) return unavailable("No Live TV channels found.")
        val now = Instant.now()
        val inGuide = config.cardTapAction == CardTapAction.OPEN_GUIDE
        val items = channels.map { channel ->
            val program = channel.current
            val image = channel.imageUri?.takeIf { it.length <= 2048 }
            ShelfItem(
                id = channel.id.take(128),
                title = channel.name.take(120),
                subtitle = listOfNotNull(program?.title, program?.remainingTime(now)).joinToString(" · ").take(160)
                    .ifBlank { null },
                channelNumber = channel.number?.take(16),
                badge = if (program?.isLive == true) "LIVE" else null,
                imageUri = image,
                imageHeaders = Bundle().apply { if (image != null) putString("Authorization", api.authorization) },
                progressPercent = program?.progressPercent(now) ?: -1,
                startTimeEpochMs = program?.start?.toEpochMilli() ?: 0,
                endTimeEpochMs = program?.end?.toEpochMilli() ?: 0,
                launchPackageName = if (inGuide) packageName else JELLYFIN_TV,
                launchActivityClassName = if (inGuide) JellyfinGuideActivity::class.java.name else null,
                launchExtras = Bundle().apply { if (inGuide) putString(CHANNEL_ID, channel.id.take(2048)) },
            )
        }.distinctBy(ShelfItem::id)
        return ShelfPayload(true, "Live TV · On Now", items, guideActivityClassName = JellyfinGuideActivity::class.java.name)
    }

    private fun unavailable(reason: String) = ShelfPayload(false, "Live TV", emptyList(), reason)

    private companion object {
        val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cacheLock = Any()
        var snapshot: JellyfinConfig? = null
        var pending: Deferred<ShelfPayload>? = null
        var lastPayload: ShelfPayload? = null
        var completedAt = 0L
    }
}
