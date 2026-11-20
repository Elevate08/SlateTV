package com.stasisstate.slatetv.connector.homeassistant

import com.stasisstate.slatetv.connector.homeassistant.HaApiClient.Companion.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class HaAuthException(message: String, val httpCode: Int = 0) : IOException(message) {
    /** The flow is gone on the server (expired/finished): start a new one instead of resubmitting. */
    val flowGone: Boolean get() = httpCode == 404 || httpCode == 409
}

data class HaAuthTokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Int)

/** What a successful sign-in hands to the settings screen: a long-lived token for [HaConfigStore]. */
data class HaSignInResult(val longLivedToken: String, val info: HaServerInfo)

/**
 * Client for Home Assistant's auth API (https://developers.home-assistant.io/docs/auth_api/):
 * login flow → authorization code → short-lived tokens → a long-lived access token minted over the
 * WebSocket (`auth/long_lived_access_token`). The rest of Slate TV only ever sees that long-lived token.
 *
 * HA counts every response ≥ 400 from the login-flow and token endpoints as a failed login toward
 * its IP ban (`http.ban.log_invalid_auth`), so this client never retries a step on its own and sends
 * only the fields every supported HA version accepts.
 *
 * Redirects are never followed (shared [HaApiClient.defaultClient]) so credentials can't be forwarded.
 */
class HaAuthClient(
    baseUrl: String,
    private val client: OkHttpClient = HaApiClient.defaultClient,
    private val webSocketClient: OkHttpClient = HaApiClient.webSocketClient,
) {
    val baseUrl: String = HaConfig.normalizeServerUrl(baseUrl)

    private fun url(path: String): HttpUrl =
        "$baseUrl$path".toHttpUrlOrNull() ?: throw HaAuthException("Invalid server address")

    /** `GET /auth/providers` (unauthenticated; not counted toward bans). */
    suspend fun providers(): List<HaAuthProvider> = withContext(Dispatchers.IO) {
        HaLoginParser.providers(json(Request.Builder().url(url("/auth/providers")).get().build()))
    }

    /** `POST /auth/login_flow` for [provider]; returns the first step (normally username + password). */
    suspend fun startFlow(provider: HaAuthProvider): HaFlowStep = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("handler", JSONArray().put(provider.type).put(provider.id ?: JSONObject.NULL))
            .put("redirect_uri", REDIRECT_URI)
            .put("type", "authorize")
        HaLoginParser.step(json(Request.Builder().url(url("/auth/login_flow")).post(body.toJsonBody()).build()))
    }

    /** `POST /auth/login_flow/{flow_id}` with the step's [values] (already converted by [HaLoginParser.values]). */
    suspend fun submit(flowId: String, values: Map<String, Any>): HaFlowStep = withContext(Dispatchers.IO) {
        val body = JSONObject().put("client_id", CLIENT_ID)
        values.forEach { (key, value) -> body.put(key, value) }
        val request = Request.Builder().url(url("/auth/login_flow/${flowId.pathSafe()}")).post(body.toJsonBody()).build()
        HaLoginParser.step(json(request))
    }

    /** Abandons a flow (user pressed Back). Best effort. */
    suspend fun cancel(flowId: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            runCatching {
                client.executeCancellable(
                    Request.Builder().url(url("/auth/login_flow/${flowId.pathSafe()}")).delete().build(),
                ).close()
            }
        }
    }

    /** `POST /auth/token` grant_type=authorization_code. */
    suspend fun exchangeCode(code: String): HaAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", CLIENT_ID)
            .build()
        val obj = json(Request.Builder().url(url("/auth/token")).post(form).build())
        HaAuthTokens(
            accessToken = obj.optString("access_token").takeIf { it.isNotBlank() }
                ?: throw HaAuthException("Home Assistant didn't return an access token"),
            refreshToken = obj.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = obj.optInt("expires_in", 1800),
        )
    }

    /**
     * Mints a long-lived access token for the signed-in user over the WebSocket. HA allows one
     * long-lived token per client_name (a duplicate fails with a bare `unknown_error`), so the name is
     * made unique against `auth/refresh_tokens` first: "Slate TV (Model)", "Slate TV (Model) 2", …
     */
    suspend fun createLongLivedToken(
        accessToken: String,
        clientName: String,
        lifespanDays: Int = LONG_LIVED_TOKEN_DAYS,
    ): String = withContext(Dispatchers.IO) {
        webSocketSession(accessToken) { call ->
            val existing = call(JSONObject().put("type", "auth/refresh_tokens")).let { result ->
                val array = result as? JSONArray ?: JSONArray()
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("client_name") }.toSet()
            }
            val name = uniqueClientName(clientName, existing)
            val token = call(
                JSONObject().put("type", "auth/long_lived_access_token")
                    .put("client_name", name)
                    .put("lifespan", lifespanDays),
            )
            (token as? String)?.takeIf { it.isNotBlank() }
                ?: throw HaAuthException("Home Assistant didn't return a long-lived token")
        }
    }

    /**
     * Revokes a refresh token (and every access token it issued). `/auth/revoke` always answers 200;
     * servers older than it get the legacy `action=revoke` on `/auth/token`. Best effort.
     */
    suspend fun revokeRefreshToken(refreshToken: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            runCatching {
                val revoked = client.executeCancellable(
                    Request.Builder().url(url("/auth/revoke"))
                        .post(FormBody.Builder().add("token", refreshToken).build()).build(),
                ).use { it.isSuccessful }
                if (!revoked) {
                    client.executeCancellable(
                        Request.Builder().url(url("/auth/token"))
                            .post(FormBody.Builder().add("token", refreshToken).add("action", "revoke").build()).build(),
                    ).close()
                }
            }
        }
    }

    /**
     * After the flow returns [code]: exchange it, mint a long-lived token named after [deviceName],
     * check that token against the REST API, and always revoke the short-lived session afterwards.
     */
    suspend fun completeSignIn(code: String, deviceName: String): HaSignInResult {
        val tokens = exchangeCode(code)
        try {
            val longLived = createLongLivedToken(tokens.accessToken, clientNameFor(deviceName))
            val info = HaApiClient(baseUrl, longLived, client).validate()
            return HaSignInResult(longLived, info)
        } finally {
            tokens.refreshToken?.let { revokeRefreshToken(it) }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────────────

    private suspend fun json(request: Request): JSONObject =
        client.executeCancellable(request).use { response -> parseResponse(response) }

    private fun parseResponse(response: Response): JSONObject {
        val text = response.body?.string().orEmpty()
        if (text.length > MAX_JSON_CHARS) throw HaAuthException("Response too large", response.code)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (response.isSuccessful) {
            return obj ?: throw HaAuthException("Not a Home Assistant response — check the address", response.code)
        }
        if (response.isRedirect) {
            throw HaAuthException("Server redirected — use the address it redirects to", response.code)
        }
        val serverMessage = obj?.let {
            it.optString("message").ifBlank { it.optString("error_description") }.ifBlank { it.optString("error") }
        }.orEmpty()
        val message = when (response.code) {
            400 -> when {
                obj?.optString("message_code") == "onboarding_required" ->
                    "Finish setting up Home Assistant in a browser first"
                serverMessage.isNotBlank() -> "Home Assistant rejected the request: $serverMessage"
                else -> "Home Assistant rejected the request (400)"
            }
            403 -> serverMessage.takeIf { it.isNotBlank() && it != "Forbidden" }
                ?: "Home Assistant refused this TV. After several failed sign-ins it bans the TV's IP " +
                "address — remove it from ip_bans.yaml and restart Home Assistant."
            404 -> if (response.request.url.encodedPath.startsWith("/auth/login_flow/")) {
                "The sign-in expired — start again"
            } else {
                "Not found — is this a Home Assistant address?"
            }
            409 -> "That step is already being checked — wait a moment and start again"
            else -> serverMessage.takeIf { it.isNotBlank() }?.let { "Home Assistant returned ${response.code}: $it" }
                ?: "Home Assistant returned ${response.code}"
        }
        throw HaAuthException(message, response.code)
    }

    /** Opens an authenticated WebSocket, runs [block] with a request/response `call`, then closes it. */
    private suspend fun <T> webSocketSession(
        accessToken: String,
        block: suspend (call: suspend (JSONObject) -> Any?) -> T,
    ): T {
        val messages = Channel<Any>(Channel.UNLIMITED)
        val wsUrl = url("/api/websocket").toString().replaceFirst(Regex("^http"), "ws")
        val socket = webSocketClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.trySend(HaAuthException(reason.ifBlank { "Home Assistant closed the connection" }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messages.trySend(HaAuthException(t.message ?: "WebSocket connection failed"))
            }
        })
        suspend fun next(): JSONObject {
            while (true) {
                when (val item = messages.receive()) {
                    is Throwable -> throw item
                    is String -> runCatching { JSONObject(item) }.getOrNull()?.let { return it }
                }
            }
        }
        try {
            return withTimeout(WEBSOCKET_TIMEOUT_MS) {
                while (true) {
                    val message = next()
                    when (message.optString("type")) {
                        "auth_required" -> socket.send(
                            JSONObject().put("type", "auth").put("access_token", accessToken).toString(),
                        )
                        "auth_invalid" -> throw HaAuthException("Home Assistant rejected the new session")
                        "auth_ok" -> break
                    }
                }
                var nextId = 1
                block { payload ->
                    val id = nextId++
                    socket.send(payload.put("id", id).toString())
                    var reply: JSONObject
                    do {
                        reply = next()
                    } while (!(reply.optString("type") == "result" && reply.optInt("id") == id))
                    if (!reply.optBoolean("success")) {
                        val error = reply.optJSONObject("error")
                        throw HaAuthException(
                            "Home Assistant couldn't complete ${payload.optString("type")}: " +
                                (error?.optString("message")?.ifBlank { null } ?: error?.optString("code") ?: "error"),
                        )
                    }
                    reply.opt("result")
                }
            }
        } finally {
            socket.close(1000, null)
            messages.close()
        }
    }

    private fun String.pathSafe(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun JSONObject.toJsonBody() = toString().toRequestBody(JSON)

    companion object {
        /**
         * IndieAuth client_id: the project's GitHub Pages site. HA requires a URL client_id and a
         * redirect_uri on the same scheme + host + port (`indieauth.verify_redirect_uri`); with both on
         * elevate08.github.io HA accepts the pair without fetching the page, so sign-in also works when
         * the HA server has no internet access. The code is returned in the JSON response; nothing is
         * ever loaded from the redirect URI.
         */
        const val CLIENT_ID = "https://elevate08.github.io/slatetv-plugins/"
        const val REDIRECT_URI = "https://elevate08.github.io/slatetv-plugins/auth/homeassistant"

        const val LONG_LIVED_TOKEN_DAYS = 3650
        private const val WEBSOCKET_TIMEOUT_MS = 20_000L
        private const val MAX_JSON_CHARS = 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun clientNameFor(deviceName: String): String {
            val device = deviceName.trim().take(40)
            return if (device.isEmpty()) "Slate TV" else "Slate TV ($device)"
        }

        internal fun uniqueClientName(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("$base $n" in taken) n++
            return "$base $n"
        }
    }
}
