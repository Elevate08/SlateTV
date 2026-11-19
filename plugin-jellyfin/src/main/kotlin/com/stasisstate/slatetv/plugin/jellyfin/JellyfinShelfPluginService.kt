package com.stasisstate.slatetv.plugin.jellyfin

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.stasisstate.slatetv.plugin.api.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.time.Instant

internal const val JELLYFIN_TV = "org.jellyfin.androidtv"
internal const val CHANNEL_ID = "channel_id"
private const val SLATE_PACKAGE = "com.stasisstate.slatetv"

class JellyfinShelfPluginService : Service() {
    private val logoCache by lazy { JellyfinLogoCache(applicationContext) }

    private val binder = object : ISlateShelfPlugin.Stub() {
        override fun getApiVersion() = PluginContract.API_VERSION

        override fun getShelfInfo() = ShelfPluginInfo(
            "com.stasisstate.slatetv.plugin.jellyfin.livetv", "Jellyfin Live TV",
            "Live TV channels and program guide from your Jellyfin server", "1.0.0",
            JellyfinSettingsActivity::class.java.name, JellyfinGuideActivity::class.java.name,
        )

        override fun getShelfPayload(options: Bundle?): ShelfPayload {
            if (!isCallerAuthorized(Binder.getCallingUid())) {
                return unavailable("Slate TV access required.")
            }
            return runCatching { runBlocking { shelf() } }
                .getOrElse { unavailable("Could not load Jellyfin Live TV.") }
        }
    }

    override fun onBind(intent: Intent?) = binder

    private fun isCallerAuthorized(callingUid: Int): Boolean {
        if (callingUid == Process.myUid()) return true
        val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
        if (SLATE_PACKAGE !in packages) return false
        val pinned = BuildConfig.TRUSTED_SLATE_CERT_SHA256
        val sigMatches = packageManager.checkSignatures(packageName, SLATE_PACKAGE) == PackageManager.SIGNATURE_MATCH
        val certs = if (pinned.isNotBlank()) signingCertSha256s(packageManager, SLATE_PACKAGE) else emptySet()
        return isTrustedJellyfinCaller(
            packages = packages,
            signatureMatchesSelf = sigMatches,
            pinnedCertSha256 = pinned,
            callerCertSha256s = certs,
        )
    }

    private suspend fun shelf(): ShelfPayload {
        val config = JellyfinConfigStore(applicationContext).load()
        val (refresh, cached) = synchronized(cacheLock) {
            if (snapshot != config) {
                pending?.cancel()
                pending = null
                lastPayload = null
                snapshot = config
            }
            if (config.serverUrl.isBlank() || config.apiKey.isBlank()) return unavailable("Configure Jellyfin first.")

            val age = SystemClock.elapsedRealtime() - completedAt
            val last = lastPayload
            val freshTtl = if (last?.available == true) SUCCESS_FRESH_MS else FAILURE_FRESH_MS
            if (last != null && age < freshTtl) return last
            val cached = last?.takeIf { it.available && age <= SUCCESS_STALE_MS }

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

        // Proxy logos through JellyfinMediaProvider so Jellyfin tokens never leave this plugin
        // and self-signed LAN HTTPS channel icons load cleanly in Slate TV.
        val proxiedLogos = coroutineScope {
            channels.map { channel ->
                async(Dispatchers.IO) {
                    val rawUrl = channel.imageUri?.takeIf { it.length <= 2048 } ?: return@async channel.id to null
                    val contentUri = withTimeoutOrNull(2_000L) {
                        logoCache.publish(api, rawUrl, channel.imageTag, SLATE_PACKAGE)?.toString()
                    }
                    channel.id to contentUri
                }
            }.awaitAll().toMap()
        }

        val items = channels.map { channel ->
            val program = channel.current
            ShelfItem(
                id = channel.id.take(128),
                title = channel.name.take(120),
                subtitle = listOfNotNull(program?.title, program?.remainingTime(now)).joinToString(" · ").take(160)
                    .ifBlank { null },
                channelNumber = channel.number?.take(16),
                badge = if (program?.isLive == true) "LIVE" else null,
                imageUri = proxiedLogos[channel.id],
                imageHeaders = Bundle(),
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
        const val SUCCESS_FRESH_MS = 45_000L
        const val SUCCESS_STALE_MS = 90_000L
        const val FAILURE_FRESH_MS = 3_000L

        val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cacheLock = Any()
        var snapshot: JellyfinConfig? = null
        var pending: Deferred<ShelfPayload>? = null
        var lastPayload: ShelfPayload? = null
        var completedAt = 0L
    }
}

internal fun isTrustedJellyfinCaller(
    packages: Array<out String>,
    signatureMatchesSelf: Boolean,
    pinnedCertSha256: String = "",
    callerCertSha256s: Set<String> = emptySet(),
): Boolean {
    if (SLATE_PACKAGE !in packages) return false
    val expected = pinnedCertSha256.trim().lowercase()
    return if (expected.isNotEmpty()) {
        expected in callerCertSha256s
    } else {
        signatureMatchesSelf
    }
}

private fun signingCertSha256s(pm: PackageManager, targetPackage: String): Set<String> = runCatching {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(targetPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = info.signingInfo ?: return@runCatching emptySet()
        if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(targetPackage, PackageManager.GET_SIGNATURES).signatures
    }
    val md = MessageDigest.getInstance("SHA-256")
    signatures.orEmpty().map { sig ->
        md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()
}.getOrDefault(emptySet())
