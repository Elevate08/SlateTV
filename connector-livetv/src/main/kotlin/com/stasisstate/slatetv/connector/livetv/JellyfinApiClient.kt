package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.coroutines.resumeWithException

data class Program(
    val id: String,
    val name: String,
    val episodeTitle: String?,
    val overview: String?,
    val start: Instant?,
    val end: Instant?,
    val isLive: Boolean,
) {
    val title: String get() = listOfNotNull(name, episodeTitle).joinToString(" · ")
    private fun validTimes() = start != null && end != null && end > start

    fun progressPercent(now: Instant): Int {
        if (!validTimes()) return -1
        val beginning = start!!.toEpochMilli()
        val duration = end!!.toEpochMilli() - beginning
        if (duration <= 0) return -1
        return (((now.toEpochMilli() - beginning).toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun remainingTime(now: Instant): String? {
        if (!validTimes() || now < start!! || now > end!!) return null
        val seconds = end.epochSecond - now.epochSecond
        val minutes = (seconds + 59) / 60
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
    }
}

data class Channel(
    val id: String,
    val name: String,
    val number: String?,
    val imageUri: String?,
    val current: Program?,
    val imageTag: String? = null,
)

internal fun InetAddress.isLanOrTailscale(): Boolean {
    if (isSiteLocalAddress || isLoopbackAddress || isLinkLocalAddress) return true
    val bytes = address
    // Tailscale / CGNAT private mesh range: 100.64.0.0/10
    if (bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64) {
        return true
    }
    // IPv6 Unique Local Address (ULA): fc00::/7
    if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) {
        return true
    }
    return false
}

private fun JSONObject.string(key: String) = if (isNull(key)) null else optString(key).trim().ifEmpty { null }
private fun JSONObject.instant(key: String) = string(key)?.let {
    runCatching { Instant.parse(it).also { time -> time.toEpochMilli() } }.getOrNull()
}

private fun JSONObject.program() = Program(
    string("Id").orEmpty(), string("Name") ?: "Untitled program", string("EpisodeTitle"),
    string("Overview"), instant("StartDate"), instant("EndDate"), optBoolean("IsLive"),
)

private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
private fun JSONObject.items() = optJSONArray("Items")?.objects().orEmpty()

/** Headers describing a request body; dropped when a 303 switches the request to bodyless GET (RFC 9110 §15.4). */
private val BODY_REPRESENTATION_HEADERS =
    listOf("Content-Type", "Content-Length", "Content-Encoding", "Content-Language", "Transfer-Encoding")

/**
 * Shared LAN-safe client: bounded timeouts, origin-pinned redirects, private-address enforcement for
 * cleartext and for servers using a user-pinned (trust-on-first-use) certificate. With a [pin], only
 * that exact leaf certificate is trusted for the configured host; otherwise normal system trust applies.
 */
internal fun buildJellyfinHttpClient(base: HttpUrl, pin: TofuPin? = null): OkHttpClient {
    val pinnedLan = pin != null && base.isHttps
    return OkHttpClient.Builder()
        .connectTimeout(6, SECONDS).readTimeout(10, SECONDS).callTimeout(15, SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            var request = chain.request()
            repeat(6) {
                val response = chain.proceed(request)
                val target = response.header("Location")?.let(request.url::resolve)
                if (response.code !in setOf(301, 302, 303, 307, 308) || target == null) {
                    return@addInterceptor response
                }
                val code = response.code
                response.close()
                val sameOrigin = request.url.origin() == target.origin()
                if (pinnedLan && !sameOrigin) throw IOException("LAN TLS redirects must stay on the configured origin.")
                if (request.url.isHttps && !target.isHttps) throw IOException("Jellyfin HTTPS cannot redirect to HTTP.")
                if (target.username.isNotEmpty() || target.password.isNotEmpty()) throw IOException("Invalid redirect.")
                request = request.newBuilder().url(target).apply {
                    if (code == 303 && request.method != "GET" && request.method != "HEAD") {
                        method("GET", null)
                        BODY_REPRESENTATION_HEADERS.forEach(::removeHeader)
                    }
                    if (!sameOrigin) {
                        removeHeader("Authorization")
                        removeHeader("X-Emby-Token")
                    }
                }.build()
            }
            throw IOException("Too many Jellyfin redirects.")
        }.apply {
            if (!base.isHttps || pinnedLan) {
                proxy(Proxy.NO_PROXY)
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val addresses = Dns.SYSTEM.lookup(hostname)
                        if (!pinnedLan && (hostname != base.host || base.isHttps)) return addresses
                        return addresses.filter { it.isLanOrTailscale() }
                            .ifEmpty { throw UnknownHostException("Jellyfin cleartext and untrusted TLS require a LAN or private mesh address.") }
                    }
                })
                addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (!request.url.isHttps && chain.connection()?.route()?.socketAddress?.address?.isLanOrTailscale() != true) {
                        throw IOException("Jellyfin HTTP requires a LAN or private mesh address.")
                    }
                    chain.proceed(request)
                }
            }
            if (pin != null && base.isHttps) {
                val tls = LiveTvTofuTls.pinnedTls(pin.certDer)
                sslSocketFactory(tls.socketFactory, tls.trustManager)
                hostnameVerifier { host, session -> LiveTvTofuTls.verifyPinnedPeer(host, base.host, session, pin.certDer) }
            }
        }.build()
}

class JellyfinApiClient(private val config: LiveTvConfig) {
    private val base = requireNotNull(parseServerUrl(config.serverUrl)) { "Enter a valid Jellyfin server URL." }
    init { require(validApiKey(config.apiKey)) { "Enter a valid API key." } }

    val authorization = """MediaBrowser Client="Slate TV Jellyfin", Device="Android TV", DeviceId="slatetv-jellyfin", Version="1.0.0", Token="${config.apiKey}""""
    internal val client = buildJellyfinHttpClient(base, config.activePin())

    suspend fun serverName() = JSONObject(get("System/Info/Public")!!).string("ServerName") ?: "Jellyfin"

    suspend fun detectUserId(): String {
        val me = get("Users/Me", allowedErrors = setOf(400, 401, 403, 404))
        me?.let { JSONObject(it).string("Id") }?.let { return it }
        return JSONArray(get("Users")!!).objects().firstNotNullOfOrNull { it.string("Id") }
            ?: throw IOException("No Jellyfin user found. Enter a user ID in Settings.")
    }

    suspend fun liveTvEnabled() = get("LiveTv/Info", allowedErrors = setOf(403, 404))
        ?.let { JSONObject(it).optBoolean("IsEnabled") } ?: false

    suspend fun channels(userId: String, limit: Int, favorites: Boolean): List<Channel> =
        channelPage(userId, limit, favorites).items

    suspend fun channelCount(userId: String): Int = channelPage(userId, 1, false).total

    suspend fun guideChannels(userId: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var start = 0
        do {
            val page = channelPage(userId, 100, false, start)
            channels += page.items
            start += page.received
        } while (page.received > 0 && start < page.total)
        return channels.distinctBy(Channel::id)
    }

    internal data class ChannelPage(val items: List<Channel>, val received: Int, val total: Int)

    internal suspend fun channelPage(userId: String, limit: Int, favorites: Boolean, start: Int = 0): ChannelPage {
        val params = linkedMapOf(
            "UserId" to userId, "AddCurrentProgram" to "true", "EnableImages" to "true",
            "EnableImageTypes" to "Primary", "Limit" to limit.toString(),
        )
        if (favorites) params["IsFavorite"] = "true"
        if (start != 0) params["StartIndex"] = start.toString()
        val data = JSONObject(get("LiveTv/Channels", params)!!)
        val items = data.items().take(limit)
        val channels = items.mapNotNull { item ->
            val id = item.string("Id") ?: return@mapNotNull null
            val name = item.string("Name") ?: return@mapNotNull null
            val primaryTag = item.optJSONObject("ImageTags")?.string("Primary")
            val image = primaryTag?.let {
                url("Items").addPathSegment(id).addPathSegments("Images/Primary")
                    .addQueryParameter("maxWidth", "240").addQueryParameter("quality", "90").build().toString()
            }
            Channel(
                id = id,
                name = name,
                number = item.string("Number") ?: item.string("ChannelNumber"),
                imageUri = image,
                current = item.optJSONObject("CurrentProgram")?.program(),
                imageTag = primaryTag,
            )
        }.distinctBy(Channel::id)
        return ChannelPage(channels, items.size, data.optInt("TotalRecordCount", items.size))
    }

    suspend fun upNext(userId: String, channelIds: List<String>, now: Instant = Instant.now()): Map<String, Program> {
        if (channelIds.isEmpty()) return emptyMap()
        val limit = (channelIds.size * 4).coerceAtLeast(80)
        val query = mapOf(
            "UserId" to userId, "ChannelIds" to channelIds.joinToString(","),
            "MaxStartDate" to now.plusSeconds(14_400).toString(), "HasAired" to "false",
            "SortBy" to "StartDate", "Limit" to limit.toString(),
        )
        return JSONObject(get("LiveTv/Programs", query)!!).items().mapNotNull { item ->
            val channel = item.string("ChannelId") ?: return@mapNotNull null
            val program = item.program()
            if (program.start == null || program.start <= now) return@mapNotNull null
            channel to program
        }.groupBy({ it.first }, { it.second }).mapValues { (_, programs) -> programs.minBy { it.start!! } }
    }

    internal suspend fun downloadImageBytes(imageUrl: String, maxBytes: Long): ByteArray? {
        val parsed = imageUrl.toHttpUrlOrNull() ?: return null
        if (parsed.origin() != base.origin()) return null
        val request = Request.Builder()
            .url(parsed)
            .header("Authorization", authorization)
            .header("Accept", "image/*")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { resp ->
                            val body = resp.body ?: return@use null
                            if (!resp.isSuccessful || body.contentType()?.type != "image" ||
                                body.contentLength() > maxBytes
                            ) {
                                return@use null
                            }
                            val source = body.source()
                            if (source.request(maxBytes + 1)) return@use null
                            source.readByteArray()
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                }
            })
        }
    }

    private fun url(path: String) = jellyfinUrl(base, path)

    private suspend fun get(path: String, query: Map<String, String> = emptyMap(), allowedErrors: Set<Int> = emptySet()): String? {
        val url = url(path).apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return client.fetchText(Request.Builder().url(url).header("Authorization", authorization).build(), allowedErrors)
    }

    companion object {
        private const val QUICK_CONNECT_CLIENT =
            """MediaBrowser Client="Slate TV Jellyfin", Device="Android TV", DeviceId="slatetv-jellyfin", Version="1.0.0""""

        private fun quickConnectBase(serverUrl: String) =
            requireNotNull(parseServerUrl(serverUrl)) { "Enter a valid Jellyfin server URL." }

        private fun quickConnectClient(base: HttpUrl, pin: TofuPin?) = buildJellyfinHttpClient(base, pin)

        private fun quickConnectRequest(base: HttpUrl, path: String, query: Map<String, String> = emptyMap()) =
            Request.Builder().url(jellyfinUrl(base, path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
                .header("Authorization", QUICK_CONNECT_CLIENT)

        /** `POST /QuickConnect/Initiate`: returns the polling secret and the 6-digit code the user approves in another Jellyfin app. */
        suspend fun initiateQuickConnect(serverUrl: String, pin: TofuPin? = null): QuickConnectSession {
            val base = quickConnectBase(serverUrl)
            val request = quickConnectRequest(base, "QuickConnect/Initiate")
                .post("".toRequestBody(JSON_MEDIA_TYPE)).build()
            val body = quickConnectClient(base, pin)
                .fetchText(request, setOf(401, 403, 404)) ?: throw IOException("Quick Connect is not enabled on this Jellyfin server.")
            val json = JSONObject(body)
            val secret = json.string("Secret")
            val code = json.string("Code")
            if (secret == null || code == null) throw IOException("Jellyfin returned an invalid Quick Connect response.")
            return QuickConnectSession(secret, code)
        }

        /** `GET /QuickConnect/Connect?secret=…`: true once the code was approved. */
        suspend fun pollQuickConnect(serverUrl: String, secret: String, pin: TofuPin? = null): Boolean {
            val base = quickConnectBase(serverUrl)
            val request = quickConnectRequest(base, "QuickConnect/Connect", mapOf("secret" to secret)).get().build()
            val body = quickConnectClient(base, pin)
                .fetchText(request, setOf(404)) ?: throw IOException("Quick Connect request expired.")
            return JSONObject(body).optBoolean("Authenticated", false)
        }

        /** `POST /Users/AuthenticateWithQuickConnect`: exchanges an approved secret for an access token and user id. */
        suspend fun authenticateWithQuickConnect(serverUrl: String, secret: String, pin: TofuPin? = null): QuickConnectAuth {
            val base = quickConnectBase(serverUrl)
            val payload = JSONObject().put("Secret", secret).toString()
            val request = quickConnectRequest(base, "Users/AuthenticateWithQuickConnect")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
            val json = JSONObject(quickConnectClient(base, pin).fetchText(request)!!)
            val token = json.string("AccessToken")?.takeIf(::validApiKey)
            val userId = json.optJSONObject("User")?.string("Id")
            if (token == null || userId == null) throw IOException("Jellyfin returned an invalid sign-in response.")
            return QuickConnectAuth(token, userId)
        }

        /**
         * Polls until the code is approved, then signs in. Returns `null` if [timeoutMs] elapses first.
         * Cancel the calling coroutine to abort.
         */
        suspend fun awaitQuickConnect(
            serverUrl: String,
            secret: String,
            pin: TofuPin? = null,
            pollIntervalMs: Long = 3_000,
            timeoutMs: Long = 300_000,
        ): QuickConnectAuth? {
            var waited = 0L
            while (waited <= timeoutMs) {
                if (pollQuickConnect(serverUrl, secret, pin)) {
                    return authenticateWithQuickConnect(serverUrl, secret, pin)
                }
                delay(pollIntervalMs)
                waited += pollIntervalMs
            }
            return null
        }
    }
}

data class QuickConnectSession(val secret: String, val code: String)
data class QuickConnectAuth(val accessToken: String, val userId: String)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun HttpUrl.origin() = Triple(scheme, host, port)

private fun jellyfinUrl(base: HttpUrl, path: String) = base.newBuilder().apply {
    if (base.encodedPath.endsWith("/")) removePathSegment(base.pathSegments.lastIndex)
    addPathSegments(path)
}

/** Executes [request] and returns its body (≤4 MiB), `null` for an [allowedErrors] status, or throws [IOException]. */
internal suspend fun OkHttpClient.fetchText(request: Request, allowedErrors: Set<Int> = emptySet()): String? =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when {
                            it.code in allowedErrors -> null
                            !it.isSuccessful -> throw IOException("Jellyfin returned HTTP ${it.code}.")
                            else -> {
                                val source = it.body?.source() ?: throw IOException("Empty Jellyfin response.")
                                if (source.request(4L * 1024 * 1024 + 1)) throw IOException("Jellyfin response is too large.")
                                source.readUtf8()
                            }
                        }
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }
