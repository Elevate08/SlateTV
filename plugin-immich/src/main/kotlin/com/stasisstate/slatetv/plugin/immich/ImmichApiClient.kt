package com.stasisstate.slatetv.plugin.immich

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

/**
 * Lightweight, header-authenticated REST client for self-hosted Immich servers (v1.115+, v2.x, v3.x).
 *
 * Security & reliability hardening:
 * - Authenticates via the `x-api-key` HTTP header (never query params).
 * - Origin-bound redirects: strips `x-api-key` if any HTTP redirect changes scheme, host, or port.
 * - Host-scoped untrusted TLS bypass: when explicitly opted in for homelab self-signed certs,
 *   hostname bypass only accepts the configured Immich server host.
 * - All OkHttp calls are cooperatively cancellable with Kotlin coroutines.
 */
class ImmichApiClient(
    private val baseStandardClient: OkHttpClient = defaultClient(allowUntrustedTls = false, allowedHost = null),
) {
    private val recentPageCounter = AtomicInteger(1)

    @Volatile
    private var cachedUntrustedHost: String? = null

    @Volatile
    private var cachedUntrustedClient: OkHttpClient? = null

    @Synchronized
    internal fun clientFor(config: ImmichConfig): OkHttpClient {
        if (!config.allowUntrustedTls) return baseStandardClient
        val host = config.normalizedServerUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return baseStandardClient
        val existing = cachedUntrustedClient
        if (existing != null && cachedUntrustedHost == host) return existing
        val created = defaultClient(allowUntrustedTls = true, allowedHost = host, baseClient = baseStandardClient)
        cachedUntrustedHost = host
        cachedUntrustedClient = created
        return created
    }

    fun validateServerUrl(rawUrl: String): String? {
        val normalized = ImmichConfig.normalizeUrl(rawUrl)
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (parsed.host.isBlank()) return null
        return normalized
    }

    fun buildAssetImageUrl(config: ImmichConfig, assetId: String): String {
        val base = config.normalizedServerUrl
        val built = base.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("assets")
            ?.addPathSegment(assetId)
            ?.addPathSegment("thumbnail")
            ?.addQueryParameter("size", config.imageQuality.apiSize)
            ?.build()
        return built?.toString()
            ?: "$base/api/assets/$assetId/thumbnail?size=${config.imageQuality.apiSize}"
    }

    fun buildAuthHeaders(config: ImmichConfig): Map<String, String> = mapOf(
        HEADER_API_KEY to config.apiKey.trim(),
        "Accept" to "image/*",
    )

    fun resetPagination() {
        recentPageCounter.set(1)
    }

    suspend fun testConnection(config: ImmichConfig): ImmichConnectionResult =
        withContext(Dispatchers.IO) {
            val base = validateServerUrl(config.serverUrl)
                ?: return@withContext ImmichConnectionResult.Failure("Invalid server URL. Use http://host:2283 or https://host.")
            if (config.apiKey.isBlank()) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "API key is empty.",
                    isAuthError = true,
                )
            }

            val safeConfig = config.copy(serverUrl = base)
            val client = clientFor(safeConfig)

            val pingOk = try {
                val pingReq = Request.Builder()
                    .url("$base/api/server/ping")
                    .get()
                    .build()
                client.executeCancellable(pingReq).use { it.isSuccessful }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SSLException) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "TLS certificate rejected. Enable 'Allow Self-Signed LAN Certs' if using a private CA.",
                    isTlsError = true,
                )
            } catch (e: IOException) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "Cannot reach $base (${e.message ?: "network error"}).",
                )
            } catch (e: IllegalArgumentException) {
                return@withContext ImmichConnectionResult.Failure("Invalid server URL: ${e.message}")
            }

            try {
                val albums = fetchAlbumsInternal(client, safeConfig)
                // Also verify asset.read + asset.view permission by testing one random asset thumbnail if available
                val sampleAssets = runCatching {
                    fetchRandomInternal(client, safeConfig, favoritesOnly = false, size = 1)
                }.getOrDefault(emptyList())
                val firstAsset = sampleAssets.firstOrNull()
                if (firstAsset != null) {
                    val thumbReq = Request.Builder()
                        .url(buildAssetImageUrl(safeConfig, firstAsset.id))
                        .header(HEADER_API_KEY, safeConfig.apiKey.trim())
                        .header("Accept", "image/*")
                        .get()
                        .build()
                    client.executeCancellable(thumbReq).use { resp ->
                        if (resp.code == 401 || resp.code == 403) {
                            throw ImmichAuthException(
                                resp.code,
                                "API key can read albums but lacks asset.view permission (HTTP ${resp.code}).",
                            )
                        }
                    }
                }
                ImmichConnectionResult.Success(
                    albumCount = albums.size,
                    serverRespondedPing = pingOk,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImmichAuthException) {
                ImmichConnectionResult.Failure(
                    message = e.message ?: "Authentication failed (HTTP ${e.code}). Check API key & scopes.",
                    isAuthError = true,
                )
            } catch (e: SSLException) {
                ImmichConnectionResult.Failure(
                    message = "TLS certificate error: ${e.message}",
                    isTlsError = true,
                )
            } catch (e: Exception) {
                ImmichConnectionResult.Failure(
                    message = e.message ?: "Unexpected error connecting to Immich.",
                )
            }
        }

    suspend fun listAlbums(config: ImmichConfig): List<ImmichAlbum> =
        withContext(Dispatchers.IO) {
            val base = validateServerUrl(config.serverUrl) ?: return@withContext emptyList()
            if (config.apiKey.isBlank()) return@withContext emptyList()
            val safeConfig = config.copy(serverUrl = base)
            fetchAlbumsInternal(clientFor(safeConfig), safeConfig)
        }

    suspend fun fetchCandidateAssets(
        config: ImmichConfig,
        batchSize: Int = 24,
    ): List<ImmichAsset> = withContext(Dispatchers.IO) {
        val base = validateServerUrl(config.serverUrl) ?: return@withContext emptyList()
        if (config.apiKey.isBlank()) return@withContext emptyList()
        val safeConfig = config.copy(serverUrl = base)
        val client = clientFor(safeConfig)
        val raw = when (safeConfig.sourceMode) {
            ImmichSourceMode.RANDOM_LIBRARY -> fetchRandomInternal(client, safeConfig, favoritesOnly = false, size = batchSize)
            ImmichSourceMode.FAVORITES -> fetchRandomInternal(client, safeConfig, favoritesOnly = true, size = batchSize)
            ImmichSourceMode.RECENT -> fetchRecentInternal(client, safeConfig, size = batchSize)
            ImmichSourceMode.ALBUMS -> fetchFromSelectedAlbumsInternal(client, safeConfig, maxItems = batchSize)
        }
        filterEligible(raw, landscapeOnly = safeConfig.landscapeOnly)
    }

    private fun filterEligible(raw: List<ImmichAsset>, landscapeOnly: Boolean): List<ImmichAsset> {
        val imagesOnly = raw.filter { it.isImage }.distinctBy { it.id }
        if (!landscapeOnly) return imagesOnly
        val landscape = imagesOnly.filter { it.isLandscape }
        return landscape.ifEmpty { imagesOnly }
    }

    private suspend fun fetchAlbumsInternal(client: OkHttpClient, config: ImmichConfig): List<ImmichAlbum> {
        val req = Request.Builder()
            .url("${config.normalizedServerUrl}/api/albums")
            .header(HEADER_API_KEY, config.apiKey.trim())
            .header("Accept", "application/json")
            .get()
            .build()

        client.executeCancellable(req).use { resp ->
            checkResponse(resp.code, resp.message)
            val body = readBoundedJsonBody(resp)
            val array = JSONArray(body)
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isNotBlank()) {
                        add(
                            ImmichAlbum(
                                id = id,
                                name = obj.optString("albumName", "Untitled Album"),
                                assetCount = obj.optInt("assetCount", 0),
                                description = obj.optString("description", ""),
                            ),
                        )
                    }
                }
            }.sortedWith(compareByDescending<ImmichAlbum> { it.assetCount }.thenBy { it.name.lowercase() })
        }
    }

    private suspend fun fetchRandomInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        favoritesOnly: Boolean,
        size: Int,
    ): List<ImmichAsset> {
        val primaryPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("visibility", "timeline")
            put("withExif", true)
            put("size", size.coerceIn(1, 100))
            if (favoritesOnly) put("isFavorite", true)
        }
        val legacyPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("isArchived", false)
            put("withExif", true)
            put("size", size.coerceIn(1, 100))
            if (favoritesOnly) put("isFavorite", true)
        }
        return postSearchWithLegacyFallback(
            client = client,
            config = config,
            path = "/api/search/random",
            primaryPayload = primaryPayload,
            legacyPayload = legacyPayload,
        ) { body ->
            parseAssetArray(JSONArray(body))
        }
    }

    private suspend fun fetchRecentInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        size: Int,
    ): List<ImmichAsset> {
        val currentPage = recentPageCounter.get().coerceAtLeast(1)
        val primaryPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("visibility", "timeline")
            put("order", "desc")
            put("size", size.coerceIn(1, 100))
            put("page", currentPage)
            put("withExif", true)
        }
        val legacyPayload = JSONObject().apply {
            put("type", "IMAGE")
            put("isArchived", false)
            put("order", "desc")
            put("size", size.coerceIn(1, 100))
            put("page", currentPage)
            put("withExif", true)
        }
        return postSearchWithLegacyFallback(
            client = client,
            config = config,
            path = "/api/search/metadata",
            primaryPayload = primaryPayload,
            legacyPayload = legacyPayload,
        ) { body ->
            val root = JSONObject(body)
            val assetsObj = root.optJSONObject("assets")
            val nextPage = assetsObj?.optString("nextPage")?.toIntOrNull()
            if (nextPage != null && nextPage > currentPage && nextPage <= MAX_RECENT_PAGES) {
                recentPageCounter.set(nextPage)
            } else {
                recentPageCounter.set(1)
            }
            val items = assetsObj?.optJSONArray("items") ?: JSONArray()
            parseAssetArray(items)
        }
    }

    /**
     * Fetches photos from selected albums using paginated `/api/search/metadata` (compatible with
     * Immich v2 AND v3 where `GET /api/albums/{id}` removed embedded `assets`), falling back to
     * `GET /api/albums/{id}` only if metadata search returns empty on an older server.
     */
    private suspend fun fetchFromSelectedAlbumsInternal(
        client: OkHttpClient,
        config: ImmichConfig,
        maxItems: Int,
    ): List<ImmichAsset> {
        val albumIds = config.selectedAlbumIds.toList()
        if (albumIds.isEmpty()) {
            return fetchRandomInternal(client, config, favoritesOnly = false, size = maxItems)
        }

        val collected = mutableListOf<ImmichAsset>()
        val sampledAlbums = albumIds.shuffled().take(4)

        // Primary path: paginated POST /api/search/metadata scoped by albumIds (Immich v2 & v3 safe)
        for (albumId in sampledAlbums) {
            val payload = JSONObject().apply {
                put("albumIds", JSONArray(listOf(albumId)))
                put("type", "IMAGE")
                put("withExif", true)
                put("size", (maxItems * 2).coerceIn(12, 100))
            }
            val req = Request.Builder()
                .url("${config.normalizedServerUrl}/api/search/metadata")
                .header(HEADER_API_KEY, config.apiKey.trim())
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val pageAssets = runCatching {
                client.executeCancellable(req).use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        checkResponse(resp.code, resp.message)
                    }
                    if (!resp.isSuccessful) return@use emptyList()
                    val root = JSONObject(readBoundedJsonBody(resp))
                    val items = root.optJSONObject("assets")?.optJSONArray("items") ?: JSONArray()
                    parseAssetArray(items)
                }
            }.getOrElse { err ->
                if (err is CancellationException || err is ImmichAuthException) throw err
                emptyList()
            }
            collected += pageAssets
        }

        // Legacy fallback: GET /api/albums/{id} (Immich v1/v2 servers that didn't support albumIds in metadata search)
        if (collected.isEmpty()) {
            for (albumId in sampledAlbums) {
                val albumUrl = config.normalizedServerUrl.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegment("api")
                    ?.addPathSegment("albums")
                    ?.addPathSegment(albumId)
                    ?.build()
                    ?: continue
                val req = Request.Builder()
                    .url(albumUrl)
                    .header(HEADER_API_KEY, config.apiKey.trim())
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.executeCancellable(req).use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        checkResponse(resp.code, resp.message)
                    }
                    if (!resp.isSuccessful) return@use
                    val obj = JSONObject(readBoundedJsonBody(resp))
                    val assets = obj.optJSONArray("assets") ?: JSONArray()
                    collected += parseAssetArray(assets)
                }
            }
        }

        // Filter images + orientation BEFORE sampling so video-heavy albums don't starve photo results
        val eligible = filterEligible(collected, landscapeOnly = config.landscapeOnly)
        return eligible.shuffled().take(maxItems * 2)
    }

    private suspend fun postSearchWithLegacyFallback(
        client: OkHttpClient,
        config: ImmichConfig,
        path: String,
        primaryPayload: JSONObject,
        legacyPayload: JSONObject,
        parse: (String) -> List<ImmichAsset>,
    ): List<ImmichAsset> {
        val url = "${config.normalizedServerUrl}$path"
        val req1 = Request.Builder()
            .url(url)
            .header(HEADER_API_KEY, config.apiKey.trim())
            .header("Accept", "application/json")
            .post(primaryPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.executeCancellable(req1).use { resp ->
            if (resp.isSuccessful) {
                return parse(readBoundedJsonBody(resp))
            }
            if (resp.code != 400) {
                checkResponse(resp.code, resp.message)
            }
        }

        // Fallback for older Immich v1.x servers rejecting `visibility`
        val req2 = Request.Builder()
            .url(url)
            .header(HEADER_API_KEY, config.apiKey.trim())
            .header("Accept", "application/json")
            .post(legacyPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.executeCancellable(req2).use { resp ->
            checkResponse(resp.code, resp.message)
            return parse(readBoundedJsonBody(resp))
        }
    }

    private fun readBoundedJsonBody(resp: Response): String {
        val body = resp.body ?: return ""
        if (body.contentLength() > MAX_JSON_BYTES) {
            throw IOException("Immich JSON response exceeds $MAX_JSON_BYTES bytes")
        }
        val source = body.source()
        if (source.request(MAX_JSON_BYTES + 1)) {
            throw IOException("Immich JSON response exceeds $MAX_JSON_BYTES bytes")
        }
        return source.readUtf8()
    }

    private fun parseAssetArray(array: JSONArray): List<ImmichAsset> {
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val exif = obj.optJSONObject("exifInfo")
                val rawWidth = exif?.optInt("exifImageWidth", 0) ?: 0
                val rawHeight = exif?.optInt("exifImageHeight", 0) ?: 0
                val orientation = exif?.optString("orientation", "")?.trim().orEmpty()
                val rotated = orientation in ROTATED_EXIF_ORIENTATIONS
                val width = if (rotated) rawHeight else rawWidth
                val height = if (rotated) rawWidth else rawHeight

                val dateTaken = exif?.optString("dateTimeOriginal")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("fileCreatedAt").takeIf { it.isNotBlank() && it != "null" }

                add(
                    ImmichAsset(
                        id = id,
                        type = obj.optString("type", "IMAGE"),
                        originalFileName = obj.optString("originalFileName", ""),
                        width = width,
                        height = height,
                        dateTakenIso = dateTaken,
                        city = exif?.optString("city")?.takeIf { it.isNotBlank() && it != "null" },
                        state = exif?.optString("state")?.takeIf { it.isNotBlank() && it != "null" },
                        country = exif?.optString("country")?.takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
    }

    private fun checkResponse(code: Int, message: String) {
        when (code) {
            in 200..299 -> Unit
            401 -> throw ImmichAuthException(401, "Invalid Immich API key (HTTP 401).")
            403 -> throw ImmichAuthException(403, "Immich API key lacks required permissions (HTTP 403).")
            else -> throw IOException("Immich server returned HTTP $code: $message")
        }
    }

    class ImmichAuthException(val code: Int, message: String) : IOException(message)

    companion object {
        const val HEADER_API_KEY = "x-api-key"

        /** Headers describing a request body; dropped when a 303 switches the request to bodyless GET (RFC 9110 §15.4). */
        private val BODY_REPRESENTATION_HEADERS =
            listOf("Content-Type", "Content-Length", "Content-Encoding", "Content-Language", "Transfer-Encoding")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ROTATED_EXIF_ORIENTATIONS = setOf("5", "6", "7", "8")
        private const val MAX_RECENT_PAGES = 10
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024

        suspend fun OkHttpClient.executeCancellable(request: Request): Response =
            newCall(request).executeCancellable()

        /** Enqueues this [Call] and suspends for its response, cancelling the call if the coroutine is cancelled. */
        suspend fun Call.executeCancellable(): Response {
            val call = this
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response) { _, discarded, _ -> discarded.close() }
                    }
                })
            }
        }

        /**
         * Builds an OkHttpClient that strips sensitive auth headers (`x-api-key`, `Authorization`)
         * on any cross-origin HTTP redirect so credentials can never leak to third-party hosts,
         * and switches HTTP 303 redirects to GET per RFC 9110.
         */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        fun defaultClient(
            allowUntrustedTls: Boolean,
            allowedHost: String? = null,
            baseClient: OkHttpClient? = null,
        ): OkHttpClient {
            val builder = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)

            if (baseClient == null) {
                builder.addInterceptor { chain ->
                    val original = chain.request()
                    var currentReq = original
                    var response = chain.proceed(currentReq)
                    var redirectCount = 0
                    while (response.isRedirect && redirectCount < 5) {
                        val location = response.header("Location") ?: break
                        val nextUrl = currentReq.url.resolve(location) ?: break
                        val sameOrigin = nextUrl.scheme == original.url.scheme &&
                            nextUrl.host.equals(original.url.host, ignoreCase = true) &&
                            nextUrl.port == original.url.port
                        val code = response.code
                        response.close()
                        val nextBuilder = currentReq.newBuilder().url(nextUrl)
                        if (code == 303 && currentReq.method != "GET" && currentReq.method != "HEAD") {
                            nextBuilder.method("GET", null)
                            BODY_REPRESENTATION_HEADERS.forEach(nextBuilder::removeHeader)
                        }
                        if (!sameOrigin) {
                            nextBuilder.removeHeader(HEADER_API_KEY)
                            nextBuilder.removeHeader("Authorization")
                            nextBuilder.removeHeader("X-Emby-Token")
                        }
                        currentReq = nextBuilder.build()
                        response = chain.proceed(currentReq)
                        redirectCount++
                    }
                    response
                }
            }

            if (allowUntrustedTls) {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAll)
                builder.hostnameVerifier { hostname, _ ->
                    // Restrict hostname bypass strictly to the explicitly configured Immich host
                    allowedHost != null && hostname.equals(allowedHost, ignoreCase = true)
                }
            }
            return builder.build()
        }
    }
}
