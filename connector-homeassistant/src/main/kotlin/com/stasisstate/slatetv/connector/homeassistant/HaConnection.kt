package com.stasisstate.slatetv.connector.homeassistant

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

sealed interface HaStatus {
    data object NotConfigured : HaStatus
    data object Connecting : HaStatus
    data object Connected : HaStatus
    data class Disconnected(val reason: String, val retryInSeconds: Int) : HaStatus
    data object AuthFailed : HaStatus
}

/**
 * Keeps one Home Assistant WebSocket open, authenticated, and subscribed (`subscribe_entities`) to
 * [entityIds], reconnecting with capped exponential backoff. Each session starts from a fresh
 * snapshot delivered via [onSnapshot]; incremental events go to [onEvent].
 */
internal class HaConnection(
    private val api: HaApiClient,
    private val token: String,
    private val entityIds: List<String>,
    private val client: OkHttpClient = HaApiClient.webSocketClient,
    private val onStatus: (HaStatus) -> Unit,
    private val onSnapshot: (JSONObject) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
) {
    private sealed interface Signal {
        data class Text(val text: String) : Signal
        data class Closed(val reason: String) : Signal
    }

    /** Runs until cancelled or until the token is rejected. */
    suspend fun run() {
        var attempt = 0
        while (coroutineContext.isActive) {
            onStatus(HaStatus.Connecting)
            val outcome = runCatching { session { attempt = 0 } }
            if (outcome.getOrNull() == SessionEnd.AUTH_FAILED) {
                onStatus(HaStatus.AuthFailed)
                return
            }
            coroutineContext.ensureActiveCompat()
            val reason = outcome.exceptionOrNull()?.message ?: "Connection closed"
            val wait = BACKOFF_SECONDS[attempt.coerceAtMost(BACKOFF_SECONDS.lastIndex)]
            attempt++
            onStatus(HaStatus.Disconnected(reason, wait))
            delay(wait * 1000L)
        }
    }

    private enum class SessionEnd { CLOSED, AUTH_FAILED }

    private suspend fun session(onSubscribed: () -> Unit): SessionEnd {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val socket = client.newWebSocket(api.webSocketRequest(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                signals.trySend(Signal.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                signals.trySend(Signal.Closed(reason.ifBlank { "Server closed the connection" }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                signals.trySend(Signal.Closed(t.message ?: "Connection failed"))
            }
        })
        try {
            var subscriptionId = -1
            var snapshotDelivered = false
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                while (true) {
                    val message = signals.receive().asMessage() ?: continue
                    when (message.optString("type")) {
                        "auth_required" -> socket.send(
                            JSONObject().put("type", "auth").put("access_token", token).toString(),
                        )
                        "auth_invalid" -> return@withTimeout
                        "auth_ok" -> {
                            subscriptionId = 1
                            val subscribe = JSONObject().put("id", subscriptionId).put("type", "subscribe_entities")
                                .put("entity_ids", JSONArray(entityIds))
                            socket.send(subscribe.toString())
                        }
                        "result" -> if (!message.optBoolean("success")) {
                            val error = message.optJSONObject("error")?.optString("message").orEmpty()
                            throw HaApiException("Subscription failed: $error")
                        }
                        "event" -> if (message.optInt("id") == subscriptionId) {
                            message.optJSONObject("event")?.let(onSnapshot)
                            snapshotDelivered = true
                            return@withTimeout
                        }
                    }
                }
            }
            if (!snapshotDelivered) return SessionEnd.AUTH_FAILED
            onStatus(HaStatus.Connected)
            onSubscribed()
            while (true) {
                when (val signal = signals.receive()) {
                    is Signal.Closed -> throw HaApiException(signal.reason)
                    is Signal.Text -> {
                        val message = signal.asMessage() ?: continue
                        if (message.optString("type") == "event" && message.optInt("id") == subscriptionId) {
                            message.optJSONObject("event")?.let(onEvent)
                        }
                    }
                }
            }
        } finally {
            socket.cancel()
            signals.close()
        }
    }

    private fun Signal.asMessage(): JSONObject? = when (this) {
        is Signal.Closed -> throw HaApiException(reason)
        is Signal.Text -> runCatching { JSONObject(text) }.getOrNull()
    }

    private fun kotlin.coroutines.CoroutineContext.ensureActiveCompat() {
        if (!isActive) throw kotlinx.coroutines.CancellationException()
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private val BACKOFF_SECONDS = intArrayOf(2, 5, 10, 20, 40, 60)
    }
}
