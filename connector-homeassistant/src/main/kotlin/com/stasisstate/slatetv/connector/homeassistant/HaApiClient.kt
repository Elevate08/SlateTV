package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HaApiException(message: String, val httpCode: Int = 0) : IOException(message)

data class HaServerInfo(val locationName: String, val version: String)

/**
 * Minimal Home Assistant REST client authenticated with a long-lived access token.
 * Redirects are never followed so the bearer token can't be forwarded to another origin.
 */
class HaApiClient(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient,
) {
    private fun url(path: String): HttpUrl =
        "${HaConfig.normalizeServerUrl(baseUrl)}$path".toHttpUrlOrNull()
            ?: throw HaApiException("Invalid server address")

    private fun request(path: String) = Request.Builder()
        .url(url(path))
        .header("Authorization", "Bearer $token")

    /** Checks the address and token, returning the instance's name and version. */
    suspend fun validate(): HaServerInfo = withContext(Dispatchers.IO) {
        executeJson(request("/api/").get().build())
        val config = executeJson(request("/api/config").get().build()) as? JSONObject
        HaServerInfo(
            locationName = config?.optString("location_name").orEmpty().ifBlank { "Home Assistant" },
            version = config?.optString("version").orEmpty(),
        )
    }

    suspend fun states(): List<HaEntity> = withContext(Dispatchers.IO) {
        val array = executeJson(request("/api/states").get().build()) as? JSONArray
            ?: throw HaApiException("Unexpected response")
        parseStates(array)
    }

    suspend fun callService(call: ServiceCall) {
        withContext(Dispatchers.IO) {
            val body = JSONObject(call.data).toString().toRequestBody(JSON)
            executeJson(request("/api/services/${call.domain}/${call.service}").post(body).build())
        }
    }

    /** Fires a custom event. Requires an admin user's token; callers treat failures as non-fatal. */
    suspend fun fireEvent(eventType: String, data: Map<String, Any?>) {
        withContext(Dispatchers.IO) {
            val body = JSONObject(data).toString().toRequestBody(JSON)
            executeJson(request("/api/events/$eventType").post(body).build())
        }
    }

    /** A single JPEG still, capped at [MAX_IMAGE_BYTES]. */
    suspend fun cameraSnapshot(entityId: String): ByteArray = withContext(Dispatchers.IO) {
        client.executeCancellable(request("/api/camera_proxy/$entityId").get().build()).use { response ->
            if (!response.isSuccessful) throw HaApiException("Camera returned ${response.code}", response.code)
            val body = response.body ?: throw HaApiException("Empty camera image")
            val length = body.contentLength()
            if (length > MAX_IMAGE_BYTES) throw HaApiException("Camera image too large")
            val source = body.source()
            if (!source.request(MAX_IMAGE_BYTES + 1L)) source.buffer.readByteArray()
            else throw HaApiException("Camera image too large")
        }
    }

    /** Opens the MJPEG stream; the caller owns (and must close) the response. */
    fun openMjpegStream(entityId: String): Call =
        streamClient.newCall(request("/api/camera_proxy_stream/$entityId").get().build())

    fun webSocketRequest(): Request {
        val http = url("/api/websocket")
        val wsUrl = http.toString().replaceFirst(Regex("^http"), "ws")
        return Request.Builder().url(wsUrl).build()
    }

    private suspend fun executeJson(request: Request): Any? =
        client.executeCancellable(request).use { response ->
            when {
                response.code == 401 || response.code == 403 ->
                    throw HaApiException("Access token was rejected", response.code)
                response.isRedirect -> throw HaApiException("Server redirected — check the address", response.code)
                response.code == 404 -> throw HaApiException("Not found — is this a Home Assistant address?", 404)
                !response.isSuccessful -> throw HaApiException("Home Assistant returned ${response.code}", response.code)
            }
            val text = response.body?.string().orEmpty()
            if (text.length > MAX_JSON_CHARS) throw HaApiException("Response too large")
            when {
                text.isBlank() -> null
                text.trimStart().startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_IMAGE_BYTES = 6L * 1024 * 1024
        private const val MAX_JSON_CHARS = 16 * 1024 * 1024

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        /** Long-lived camera streams: no call timeout, but a stalled read still fails. */
        val streamClient: OkHttpClient by lazy {
            defaultClient.newBuilder()
                .callTimeout(0, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        val webSocketClient: OkHttpClient by lazy {
            defaultClient.newBuilder()
                .callTimeout(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

        internal fun parseStates(array: JSONArray): List<HaEntity> =
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val entityId = obj.optString("entity_id").takeIf { it.contains('.') } ?: return@mapNotNull null
                HaEntity(
                    entityId = entityId,
                    state = obj.optString("state", HaEntity.STATE_UNKNOWN),
                    attributes = obj.optJSONObject("attributes")?.let(HaEntityStore::toMap).orEmpty(),
                    lastChanged = isoSeconds(obj.optString("last_changed")),
                    lastUpdated = isoSeconds(obj.optString("last_updated")),
                )
            }

        private fun isoSeconds(value: String): Double = runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli() / 1000.0
        }.getOrDefault(0.0)

        suspend fun OkHttpClient.executeCancellable(request: Request): Response {
            val call = newCall(request)
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) cont.resume(response) else response.close()
                    }
                })
            }
        }
    }
}
