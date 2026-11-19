package dev.slatetv.plugin.immich

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Tiny, zero-dependency, on-demand local HTTP setup server for entering
 * Immich URL + API key from a phone browser instead of typing with a TV remote.
 *
 * Security model:
 * - Only runs while the Immich Phone Pairing dialog is open on the TV.
 * - Protected by a per-session cryptographically random 6-digit PIN.
 * - Rejects requests missing/mismatching the PIN with HTTP 403.
 * - Stops immediately via [close] once configured or dismissed.
 */
class LanConfigServer(
    private val initialUrl: String = "",
    private val initialAllowUntrustedTls: Boolean = false,
    private val port: Int = 0,
    private val onCredentialsSubmitted: (serverUrl: String, apiKey: String, allowUntrustedTls: Boolean) -> Unit,
) : Closeable {

    val pin: String = "%06d".format(SecureRandom().nextInt(1_000_000))

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): String {
        val socket = ServerSocket(port).apply {
            reuseAddress = true
        }
        serverSocket = socket
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = localScope
        acceptJob = localScope.launch {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                launch { handleClient(client) }
            }
        }
        val ip = detectLocalIpv4() ?: "127.0.0.1"
        return "http://$ip:${socket.localPort}/?pin=$pin"
    }

    override fun close() {
        acceptJob?.cancel()
        scope?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope = null
        acceptJob = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5_000
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeResponse(output, 400, "text/plain", "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")

            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val headerName = line.substring(0, colon).trim().lowercase()
                    val headerValue = line.substring(colon + 1).trim()
                    if (headerName == "content-length") {
                        contentLength = headerValue.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                    }
                }
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val queryParams = parseForm(query)
                    val prefillPin = queryParams["pin"].orEmpty()
                    writeResponse(output, 200, "text/html; charset=utf-8", renderPage(prefillPin, null))
                }

                method == "POST" && path == "/save" -> {
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    val form = parseForm(String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8))
                    val submittedPin = form["pin"]?.trim().orEmpty()
                    if (submittedPin != pin) {
                        writeResponse(
                            output,
                            403,
                            "text/html; charset=utf-8",
                            renderPage("", "Invalid pairing PIN. Check the 6-digit code shown on your TV."),
                        )
                        return
                    }
                    val serverUrl = ImmichConfig.normalizeUrl(form["serverUrl"].orEmpty())
                    val apiKey = form["apiKey"]?.trim().orEmpty()
                    val allowUntrusted = form["allowUntrustedTls"] == "on" || form["allowUntrustedTls"] == "true"

                    if (serverUrl.isBlank() || apiKey.isBlank()) {
                        writeResponse(
                            output,
                            400,
                            "text/html; charset=utf-8",
                            renderPage(submittedPin, "Both Server URL and API Key are required."),
                        )
                        return
                    }

                    onCredentialsSubmitted(serverUrl, apiKey, allowUntrusted)
                    writeResponse(output, 200, "text/html; charset=utf-8", renderSuccessPage(serverUrl))
                }

                else -> writeResponse(output, 404, "text/plain", "Not Found")
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun parseForm(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return buildMap {
            for (pair in raw.split('&')) {
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    put(key, value)
                }
            }
        }
    }

    private fun writeResponse(output: OutputStream, code: Int, contentType: String, body: String) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun renderPage(prefillPin: String, error: String?): String {
        val errorHtml = if (!error.isNullOrBlank()) {
            """<div class="error">${escapeHtml(error)}</div>"""
        } else {
            ""
        }
        val checkedAttr = if (initialAllowUntrustedTls) "checked" else ""
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>Slate TV · Immich Setup</title>
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                       background: #0a0d12; color: #f1f5f9; margin: 0; padding: 24px; }
                .card { max-width: 440px; margin: 24px auto; background: #141923;
                        border: 1px solid #263042; border-radius: 16px; padding: 24px; }
                h1 { font-size: 20px; margin: 0 0 6px; font-weight: 600; }
                p { font-size: 14px; color: #94a3b8; margin: 0 0 20px; line-height: 1.5; }
                label { display: block; font-size: 13px; color: #cbd5e1; margin: 14px 0 6px; }
                input[type="text"], input[type="url"], input[type="password"] {
                  width: 100%; box-sizing: border-box; padding: 12px; border-radius: 10px;
                  border: 1px solid #334155; background: #0d1118; color: #f8fafc; font-size: 15px;
                }
                .check { display: flex; align-items: center; gap: 10px; margin-top: 16px; font-size: 13px; color: #cbd5e1; }
                button { margin-top: 22px; width: 100%; padding: 13px; border-radius: 10px; border: 0;
                         background: #f8fafc; color: #0a0d12; font-weight: 600; font-size: 15px; cursor: pointer; }
                .error { background: #3b1219; border: 1px solid #991b1b; color: #fecaca;
                         padding: 10px 12px; border-radius: 10px; font-size: 13px; margin-bottom: 14px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Slate TV · Immich Setup</h1>
                <p>Enter your self-hosted Immich server URL and API key. Credentials are sent directly over your local network to your TV.</p>
                $errorHtml
                <form method="POST" action="/save">
                  <label for="pin">TV Pairing PIN</label>
                  <input id="pin" name="pin" type="text" inputmode="numeric" required value="${escapeHtml(prefillPin)}" placeholder="6-digit PIN from TV"/>
                  <label for="serverUrl">Immich Server URL</label>
                  <input id="serverUrl" name="serverUrl" type="url" required value="${escapeHtml(initialUrl)}" placeholder="http://192.168.1.50:2283"/>
                  <label for="apiKey">Immich API Key</label>
                  <input id="apiKey" name="apiKey" type="password" required placeholder="Paste API key from Immich Account Settings"/>
                  <label class="check">
                    <input type="checkbox" name="allowUntrustedTls" $checkedAttr/>
                    Allow self-signed LAN HTTPS certificate
                  </label>
                  <button type="submit">Send to TV</button>
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderSuccessPage(serverUrl: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width,initial-scale=1"/>
          <title>Slate TV · Connected</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                   background: #0a0d12; color: #f1f5f9; margin: 0; padding: 24px; }
            .card { max-width: 440px; margin: 40px auto; background: #141923;
                    border: 1px solid #1e3a2f; border-radius: 16px; padding: 24px; text-align: center; }
            h1 { font-size: 20px; color: #86efac; margin: 0 0 10px; }
            p { font-size: 14px; color: #cbd5e1; line-height: 1.5; }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>Sent to your TV</h1>
            <p>Slate TV received your Immich configuration for <strong>${escapeHtml(serverUrl)}</strong>. You can close this tab and pick your albums on the TV.</p>
          </div>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val MAX_BODY_BYTES = 16 * 1024

        fun detectLocalIpv4(): String? {
            return runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var fallback: String? = null
                for (iface in interfaces.toList()) {
                    if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                    for (addr in iface.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (addr.isSiteLocalAddress) return ip
                            if (fallback == null) fallback = ip
                        }
                    }
                }
                fallback
            }.getOrNull()
        }
    }
}
