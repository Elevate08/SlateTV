package dev.slatetv.plugin.immich

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Lightweight, header-authenticated REST client for self-hosted Immich servers.
 *
 * Security notes:
 * - Always authenticates via the `x-api-key` HTTP header (never query params).
 * - Short timeouts ensure an offline LAN server never stalls the TV launcher.
 * - Strict TLS by default; untrusted LAN certs require explicit user opt-in.
 */
class ImmichApiClient(
    private val standardClient: OkHttpClient = defaultClient(allowUntrustedTls = false),
    private val untrustedClient: OkHttpClient = defaultClient(allowUntrustedTls = true),
) {
    private fun clientFor(config: ImmichConfig): OkHttpClient =
        if (config.allowUntrustedTls) untrustedClient else standardClient

    fun buildAssetImageUrl(config: ImmichConfig, assetId: String): String {
        val base = config.normalizedServerUrl
        return "$base/api/assets/$assetId/thumbnail?size=${config.imageQuality.apiSize}"
    }

    fun buildAuthHeaders(config: ImmichConfig): Map<String, String> = mapOf(
        HEADER_API_KEY to config.apiKey.trim(),
        "Accept" to "image/*",
    )

    suspend fun testConnection(config: ImmichConfig): ImmichConnectionResult =
        withContext(Dispatchers.IO) {
            if (config.normalizedServerUrl.isBlank()) {
                return@withContext ImmichConnectionResult.Failure("Server URL is empty.")
            }
            if (config.apiKey.isBlank()) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "API key is empty.",
                    isAuthError = true,
                )
            }

            val client = clientFor(config)
            val base = config.normalizedServerUrl

            val pingOk = try {
                val pingReq = Request.Builder()
                    .url("$base/api/server/ping")
                    .get()
                    .build()
                client.newCall(pingReq).execute().use { it.isSuccessful }
            } catch (e: SSLException) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "TLS certificate rejected. Enable 'Allow Self-Signed LAN Certs' if using a local private CA.",
                    isTlsError = true,
                )
            } catch (e: IOException) {
                return@withContext ImmichConnectionResult.Failure(
                    message = "Cannot reach $base (${e.message ?: "network error"}).",
                )
            }

            try {
                val albums = fetchAlbumsBlocking(client, config)
                ImmichConnectionResult.Success(
                    albumCount = albums.size,
                    serverRespondedPing = pingOk,
                )
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
            if (!config.isConfigured) return@withContext emptyList()
            fetchAlbumsBlocking(clientFor(config), config)
        }

    suspend fun fetchCandidateAssets(
        config: ImmichConfig,
        batchSize: Int = 24,
    ): List<ImmichAsset> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext emptyList()
        val client = clientFor(config)
        val raw = when (config.sourceMode) {
            ImmichSourceMode.RANDOM_LIBRARY -> fetchRandom(client, config, favoritesOnly = false, size = batchSize)
            ImmichSourceMode.FAVORITES -> fetchRandom(client, config, favoritesOnly = true, size = batchSize)
            ImmichSourceMode.RECENT -> fetchRecent(client, config, size = batchSize)
            ImmichSourceMode.ALBUMS -> fetchFromSelectedAlbums(client, config, maxItems = batchSize)
        }
        val imagesOnly = raw.filter { it.isImage }
        if (config.landscapeOnly) {
            val landscape = imagesOnly.filter { it.isLandscape }
            landscape.ifEmpty { imagesOnly }
        } else {
            imagesOnly
        }
    }

    private fun fetchAlbumsBlocking(client: OkHttpClient, config: ImmichConfig): List<ImmichAlbum> {
        val req = Request.Builder()
            .url("${config.normalizedServerUrl}/api/albums")
            .header(HEADER_API_KEY, config.apiKey.trim())
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            checkResponse(resp.code, resp.message)
            val body = resp.body?.string().orEmpty()
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

    private fun fetchRandom(
        client: OkHttpClient,
        config: ImmichConfig,
        favoritesOnly: Boolean,
        size: Int,
    ): List<ImmichAsset> {
        val payload = JSONObject().apply {
            put("type", "IMAGE")
            put("isArchived", false)
            put("size", size.coerceIn(1, 100))
            if (favoritesOnly) {
                put("isFavorite", true)
            }
        }
        val req = Request.Builder()
            .url("${config.normalizedServerUrl}/api/search/random")
            .header(HEADER_API_KEY, config.apiKey.trim())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(req).execute().use { resp ->
            checkResponse(resp.code, resp.message)
            val body = resp.body?.string().orEmpty()
            return parseAssetArray(JSONArray(body))
        }
    }

    private fun fetchRecent(
        client: OkHttpClient,
        config: ImmichConfig,
        size: Int,
    ): List<ImmichAsset> {
        val payload = JSONObject().apply {
            put("type", "IMAGE")
            put("isArchived", false)
            put("order", "desc")
            put("size", size.coerceIn(1, 100))
            put("withExif", true)
        }
        val req = Request.Builder()
            .url("${config.normalizedServerUrl}/api/search/metadata")
            .header(HEADER_API_KEY, config.apiKey.trim())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(req).execute().use { resp ->
            checkResponse(resp.code, resp.message)
            val body = resp.body?.string().orEmpty()
            val root = JSONObject(body)
            val items = root.optJSONObject("assets")?.optJSONArray("items") ?: JSONArray()
            return parseAssetArray(items)
        }
    }

    private fun fetchFromSelectedAlbums(
        client: OkHttpClient,
        config: ImmichConfig,
        maxItems: Int,
    ): List<ImmichAsset> {
        val albumIds = config.selectedAlbumIds.toList()
        if (albumIds.isEmpty()) {
            return fetchRandom(client, config, favoritesOnly = false, size = maxItems)
        }

        val collected = mutableListOf<ImmichAsset>()
        // Pick up to 4 albums per refresh round so we stay ultra-lightweight on LAN
        for (albumId in albumIds.shuffled().take(4)) {
            val req = Request.Builder()
                .url("${config.normalizedServerUrl}/api/albums/$albumId")
                .header(HEADER_API_KEY, config.apiKey.trim())
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        checkResponse(resp.code, resp.message)
                    }
                    return@use
                }
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val assets = obj.optJSONArray("assets") ?: JSONArray()
                collected += parseAssetArray(assets)
            }
        }
        return collected.shuffled().take(maxItems * 2)
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ROTATED_EXIF_ORIENTATIONS = setOf("5", "6", "7", "8")

        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        fun defaultClient(allowUntrustedTls: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)

            if (allowUntrustedTls) {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAll)
                builder.hostnameVerifier { _, _ -> true }
            }
            return builder.build()
        }
    }
}
