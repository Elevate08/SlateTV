package com.stasisstate.slatetv.connector.homeassistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Zero-typing setup from a computer:
 *
 * ```
 * adb shell am broadcast -a com.stasisstate.slatetv.action.HA_CONFIGURE \
 *   -n com.stasisstate.slatetv/com.stasisstate.slatetv.connector.homeassistant.HaAdbConfigReceiver \
 *   --es url https://ha.example.com --es token "$(cat token)"
 * ```
 *
 * Exported but guarded by `android.permission.DUMP` (signature|privileged|development): the adb
 * shell user holds it, ordinary apps can't, so only someone with adb access can configure the TV.
 * The token is checked against the server once, then saved through [HaConfigStore] (Keystore
 * encrypted) via [HomeAssistantRuntime.updateConfig]. The result code/data reach the `am` caller;
 * the token is never logged or echoed.
 */
class HaAdbConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HaProvisioning.ACTION) return
        val request = HaProvisioning.validate(
            url = intent.getStringExtra(HaProvisioning.EXTRA_URL),
            token = intent.getStringExtra(HaProvisioning.EXTRA_TOKEN),
        )
        if (request is HaProvisioning.Check.Rejected) {
            finish(request.outcome)
            return
        }
        request as HaProvisioning.Check.Valid
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var outcome = HaProvisioning.Outcome(HaProvisioning.RESULT_ERROR, "ERROR: unexpected failure")
            try {
                outcome = configure(context.applicationContext, request)
            } catch (e: Throwable) {
                Log.w(TAG, "ADB configure failed: ${e.javaClass.simpleName}")
            } finally {
                Log.i(TAG, "ADB configure result=${outcome.code} (${request.host})")
                pending.setResult(outcome.code, outcome.data, null)
                pending.finish()
            }
        }
    }

    private fun finish(outcome: HaProvisioning.Outcome) {
        Log.i(TAG, "ADB configure rejected: ${outcome.data}")
        if (isOrderedBroadcast) setResult(outcome.code, outcome.data, null)
        else setResultData(outcome.data)
    }

    private suspend fun configure(context: Context, request: HaProvisioning.Check.Valid): HaProvisioning.Outcome {
        val runtime = HomeAssistantRuntime
        if (!runtime.isInitialized) {
            runtime.init(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
        val info = try {
            withTimeout(VALIDATE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { HaApiClient(request.serverUrl, request.token).validate() }
            }
        } catch (e: TimeoutCancellationException) {
            return HaProvisioning.Outcome(HaProvisioning.RESULT_UNREACHABLE, "ERROR: timed out reaching ${request.serverUrl}")
        } catch (e: HaApiException) {
            return HaProvisioning.failure(request.serverUrl, e.httpCode, e.message)
        } catch (e: java.io.IOException) {
            return HaProvisioning.failure(request.serverUrl, 0, e.message ?: e.javaClass.simpleName)
        }
        runtime.updateConfig(runtime.config.value.copy(serverUrl = request.serverUrl, token = request.token))
        val note = if (runtime.enabled.value) "" else " (Home Assistant plugin is uninstalled in Slate TV; install it to use)"
        return HaProvisioning.Outcome(
            Activity.RESULT_OK,
            "OK: saved; connected to ${info.locationName}${info.version.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}$note",
        )
    }

    companion object {
        private const val TAG = "SlateHA"
        private const val VALIDATE_TIMEOUT_MS = 20_000L
    }
}

/** Pure request validation + result codes for [HaAdbConfigReceiver] (unit-tested on the JVM). */
object HaProvisioning {
    const val ACTION = "com.stasisstate.slatetv.action.HA_CONFIGURE"
    const val EXTRA_URL = "url"
    const val EXTRA_TOKEN = "token"

    const val RESULT_OK = -1 // Activity.RESULT_OK
    const val RESULT_BAD_REQUEST = 2
    const val RESULT_TOKEN_REJECTED = 3
    const val RESULT_UNREACHABLE = 4
    const val RESULT_ERROR = 5

    private const val MAX_TOKEN_CHARS = 4096
    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~+/=-]+$")

    data class Outcome(val code: Int, val data: String)

    sealed interface Check {
        data class Valid(val serverUrl: String, val token: String) : Check {
            val host: String get() = runCatching { java.net.URI(serverUrl).host }.getOrNull().orEmpty()
            override fun toString() = "Valid(serverUrl=$serverUrl, token=<redacted>)"
        }
        data class Rejected(val outcome: Outcome) : Check
    }

    fun validate(url: String?, token: String?): Check {
        val rawUrl = url?.trim().orEmpty()
        if (rawUrl.isEmpty()) return bad("missing --es $EXTRA_URL <https://your-home-assistant:8123>")
        if (!rawUrl.contains("://")) return bad("url must start with http:// or https://")
        val normalized = HaConfig.normalizeServerUrl(rawUrl)
        if (normalized.isBlank()) return bad("url is not a valid http(s) address")
        val cleanToken = token?.trim().orEmpty()
        if (cleanToken.isEmpty()) return bad("missing --es $EXTRA_TOKEN <long-lived access token>")
        if (cleanToken.length > MAX_TOKEN_CHARS || !TOKEN_CHARS.matches(cleanToken)) {
            return bad("token has unexpected characters (paste only the token; quote it in the shell)")
        }
        return Check.Valid(normalized, cleanToken)
    }

    fun failure(serverUrl: String, httpCode: Int, message: String?): Outcome = when (httpCode) {
        401, 403 -> Outcome(RESULT_TOKEN_REJECTED, "ERROR: token rejected by $serverUrl")
        0 -> Outcome(RESULT_UNREACHABLE, "ERROR: couldn't reach $serverUrl: ${message.orEmpty().take(160)}")
        else -> Outcome(RESULT_ERROR, "ERROR: ${message.orEmpty().take(160)}")
    }

    private fun bad(message: String) = Check.Rejected(Outcome(RESULT_BAD_REQUEST, "ERROR: $message"))

    /** The documented command, shown in Settings → Advanced. */
    fun adbCommand(packageName: String, serverUrl: String): String =
        "adb shell am broadcast -a $ACTION -n $packageName/${HaAdbConfigReceiver::class.java.name} " +
            "--es url ${serverUrl.ifBlank { "https://your-home-assistant:8123" }} --es token \"\$(cat token.txt)\""
}
