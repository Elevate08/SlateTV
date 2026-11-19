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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.time.Instant

internal const val JELLYFIN_TV = "org.jellyfin.androidtv"
internal const val CHANNEL_ID = "channel_id"
private const val SLATE_PACKAGE = "com.stasisstate.slatetv"

internal const val SUCCESS_FRESH_MS = 45_000L
internal const val FAILURE_FRESH_MS = 3_000L
internal const val PARTIAL_LOGO_FRESH_MS = 10_000L
internal const val LOGO_FETCH_PERMITS = 4
internal const val LOGO_FETCH_TIMEOUT_MS = 2_000L

internal fun shelfFreshTtlMs(available: Boolean, hasMissingLogos: Boolean): Long = when {
    !available -> FAILURE_FRESH_MS
    hasMissingLogos -> PARTIAL_LOGO_FRESH_MS
    else -> SUCCESS_FRESH_MS
}

/** Hash of server, user and API key; never contains or reveals the raw secret. */
internal fun logoNamespace(config: JellyfinConfig, userId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${config.serverUrl}\u0000$userId\u0000${config.apiKey}".toByteArray())
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

internal class LogoProxyResult(val logos: Map<String, String?>, val hasMissingLogos: Boolean)

/**
 * Proxies logos with at most [semaphore] concurrent fetches. The per-item [timeoutMs] starts only
 * after a permit is acquired, so queued items still get their full budget once admitted.
 */
internal suspend fun <T> proxyLogos(
    channels: List<T>,
    idOf: (T) -> String,
    rawUrlOf: (T) -> String?,
    semaphore: Semaphore,
    timeoutMs: Long = LOGO_FETCH_TIMEOUT_MS,
    fetch: suspend (T, String) -> String?,
): LogoProxyResult {
    val results = coroutineScope {
        channels.map { channel ->
            async(Dispatchers.IO) {
                val rawUrl = rawUrlOf(channel)?.takeIf { it.length <= 2048 }
                    ?: return@async Triple(idOf(channel), null, rawUrlOf(channel) != null)
                val uri = semaphore.withPermit {
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            fetch(channel, rawUrl)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                Triple(idOf(channel), uri, uri == null)
            }
        }.awaitAll()
    }
    return LogoProxyResult(
        logos = results.associate { it.first to it.second },
        hasMissingLogos = results.any { it.third },
    )
}

internal class ShelfFetch(val payload: ShelfPayload, val hasMissingLogos: Boolean = false)

class JellyfinShelfPluginService : Service() {
    private val logoCache by lazy { JellyfinLogoCache(applicationContext) }

    private val binder = object : ISlateShelfPlugin.Stub() {
        override fun getApiVersion() = PluginContract.API_VERSION

        override fun getShelfInfo() = ShelfPluginInfo(
            "com.stasisstate.slatetv.plugin.jellyfin.livetv", "Jellyfin Live TV",
            "Live TV channels and program guide from your Jellyfin server", "1.0.0",
            JellyfinSettingsActivity::class.java.name, JellyfinGuideActivity::class.java.name,
        )

        override fun getShelfPayload(options: Bundle?): ShelfPayload? {
            if (!isCallerAuthorized(Binder.getCallingUid())) {
                return unavailable("Slate TV access required.")
            }
            // Unexpected failures are transient: null lets the host keep its last good shelf.
            return runCatching { runBlocking { shelf() } }.getOrNull()
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

    /**
     * Returns a confirmed payload, an eligible stale success, or `null` for a transient failure
     * (cold refresh still running, timeout, network error) so the host keeps its previous shelf
     * and schedules a short retry instead of hiding Live TV.
     */
    private suspend fun shelf(): ShelfPayload? {
        val config = JellyfinConfigStore(applicationContext).load()
        if (config.serverUrl.isBlank() || config.apiKey.isBlank()) return unavailable("Configure Jellyfin first.")
        return shelfCache.getFetch(config, ::fetchShelf)
    }

    private suspend fun fetchShelf(config: JellyfinConfig): ShelfFetch {
        val api = JellyfinApiClient(config)
        if (!api.liveTvEnabled()) return ShelfFetch(unavailable("Live TV is unavailable on this server."))
        val user = config.userId.ifBlank { api.detectUserId() }
        val channels = api.channels(user, config.maxHomeChannels.coerceIn(1, 24), config.favoritesOnly)
        if (channels.isEmpty()) return ShelfFetch(unavailable("No Live TV channels found."))
        val now = Instant.now()
        val inGuide = config.cardTapAction == CardTapAction.OPEN_GUIDE

        // Proxy logos through JellyfinMediaProvider so Jellyfin tokens never leave this plugin
        // and self-signed LAN HTTPS channel icons load cleanly in Slate TV.
        val namespace = logoNamespace(config, user)
        val proxied = proxyLogos(
            channels = channels,
            idOf = { it.id },
            rawUrlOf = { it.imageUri },
            semaphore = logoSemaphore,
        ) { channel, rawUrl ->
            logoCache.publish(api, rawUrl, channel.imageTag, SLATE_PACKAGE, namespace)?.toString()
        }
        val proxiedLogos = proxied.logos

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
        return ShelfFetch(
            ShelfPayload(true, "Live TV · On Now", items, guideActivityClassName = JellyfinGuideActivity::class.java.name),
            hasMissingLogos = proxied.hasMissingLogos,
        )
    }

    private fun unavailable(reason: String) = ShelfPayload(false, "Live TV", emptyList(), reason)

    private companion object {
        val logoSemaphore = Semaphore(LOGO_FETCH_PERMITS)
        val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shelfCache = JellyfinShelfCache(refreshScope, SystemClock::elapsedRealtime)
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
