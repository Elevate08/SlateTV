package com.stasisstate.slatetv.plugin.immich

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
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardened, zero-dependency, on-demand local HTTP setup server for entering
 * Immich URL + API key from a phone browser instead of typing with a TV remote.
 *
 * Security & resource bounds:
 * - Only runs while the Immich Phone Pairing dialog is open on the TV.
 * - Binds specifically to the LAN interface shown in the QR code.
 * - Caps concurrent connections ([MAX_CONCURRENT_CLIENTS]), header count/bytes, body size, and request time.
 * - Protects the 6-digit PIN with a strict attempt cap ([MAX_FAILED_PIN_ATTEMPTS]) + delay.
 * - Single-use session: atomically consumes on the first valid submission so it cannot be overwritten.
 * - Tracks and closes all open client sockets immediately on [close].
 */
class LanConfigServer(
    private val initialUrl: String = "",
    private val initialAllowUntrustedTls: Boolean = false,
    private val port: Int = 0,
    private val bindAddressOverride: InetAddress? = null,
    private val onCredentialsSubmitted: (serverUrl: String, apiKey: String, allowUntrustedTls: Boolean) -> Unit,
) : Closeable {

    val pin: String = "%06d".format(SecureRandom().nextInt(1_000_000))

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private var boundHostDisplay: String = "127.0.0.1"

    private val clientPermits = Semaphore(MAX_CONCURRENT_CLIENTS)
    private val activeClients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val failedPinAttempts = AtomicInteger(0)
    private val sessionConsumed = AtomicBoolean(false)

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): String {
        val lanIp = bindAddressOverride?.hostAddress ?: detectLocalIpv4()
        val bindAddr = bindAddressOverride ?: lanIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val socket = runCatching {
            ServerSocket(port, 8, bindAddr).apply { reuseAddress = true }
        }.getOrElse {
            ServerSocket(port, 8).apply { reuseAddress = true }
        }
        serverSocket = socket
        boundHostDisplay = lanIp ?: "127.0.0.1"

        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = localScope
        acceptJob = localScope.launch {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                if (!clientPermits.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                activeClients.add(client)
                launch {
                    try {
                        handleClient(client)
                    } catch (_: Exception) {
                        // Isolate malformed client I/O errors per socket
                    } finally {
                        activeClients.remove(client)
                        runCatching { client.close() }
                        clientPermits.release()
                    }
                }
            }
        }
        return "http://$boundHostDisplay:${socket.localPort}/?pin=$pin"
    }

    override fun close() {
        acceptJob?.cancel()
        scope?.cancel()
        runCatching { serverSocket?.close() }
        synchronized(activeClients) {
            activeClients.forEach { runCatching { it.close() } }
            activeClients.clear()
        }
        serverSocket = null
        scope = null
        acceptJob = null
    }

    private fun handleClient(socket: Socket) {
        val deadlineMs = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        socket.soTimeout = 3_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        val requestLine = readLineBounded(input, MAX_LINE_BYTES, deadlineMs) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            writeResponse(output, 400, "text/plain", "Bad Request")
            return
        }
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val path = rawTarget.substringBefore('?')
        val query = rawTarget.substringAfter('?', "")

        var contentLength: Int? = null
        var headerLines = 0
        var totalHeaderBytes = requestLine.length
        var hostHeader: String? = null
        var transferEncodingChunked = false

        while (true) {
            val line = readLineBounded(input, MAX_LINE_BYTES, deadlineMs) ?: break
            if (line.isEmpty()) break
            headerLines++
            totalHeaderBytes += line.length
            if (headerLines > MAX_HEADER_LINES || totalHeaderBytes > MAX_TOTAL_HEADER_BYTES) {
                writeResponse(output, 431, "text/plain", "Request Header Fields Too Large")
                return
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                val headerName = line.substring(0, colon).trim().lowercase()
                val headerValue = line.substring(colon + 1).trim()
                when (headerName) {
                    "content-length" -> {
                        val parsed = headerValue.toIntOrNull()
                        if (parsed == null || parsed < 0 || parsed > MAX_BODY_BYTES) {
                            writeResponse(output, 400, "text/plain", "Invalid Content-Length")
                            return
                        }
                        contentLength = parsed
                    }
                    "transfer-encoding" -> {
                        if (headerValue.contains("chunked", ignoreCase = true)) {
                            transferEncodingChunked = true
                        }
                    }
                    "host" -> hostHeader = headerValue
                }
            }
        }

        if (transferEncodingChunked) {
            writeResponse(output, 400, "text/plain", "Chunked transfer encoding not supported")
            return
        }

        // Verify Host header matches this local pairing server (prevents DNS rebinding)
        if (hostHeader != null && !isExpectedHost(hostHeader)) {
            writeResponse(output, 403, "text/plain", "Forbidden Host")
            return
        }

        if (sessionConsumed.get()) {
            writeResponse(output, 410, "text/plain", "Pairing session already completed")
            return
        }

        if (failedPinAttempts.get() >= MAX_FAILED_PIN_ATTEMPTS) {
            writeResponse(output, 429, "text/plain", "Too many invalid PIN attempts. Reopen QR pairing on TV.")
            return
        }

        when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                val queryParams = parseForm(query)
                val prefillPin = queryParams["pin"].orEmpty().take(6)
                writeResponse(output, 200, "text/html; charset=utf-8", renderPage(prefillPin, null))
            }

            method == "POST" && path == "/save" -> {
                val expectedLen = contentLength
                if (expectedLen == null || expectedLen <= 0) {
                    writeResponse(output, 400, "text/plain", "Missing or invalid Content-Length")
                    return
                }
                val bodyBytes = ByteArray(expectedLen)
                var readTotal = 0
                while (readTotal < expectedLen) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        writeResponse(output, 408, "text/plain", "Request Timeout")
                        return
                    }
                    val n = input.read(bodyBytes, readTotal, expectedLen - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                if (readTotal != expectedLen) {
                    writeResponse(output, 400, "text/plain", "Incomplete request body")
                    return
                }

                val form = parseForm(String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8))
                val submittedPin = form["pin"]?.trim().orEmpty()
                if (submittedPin != pin) {
                    val attempts = failedPinAttempts.incrementAndGet()
                    Thread.sleep(250L)
                    if (attempts >= MAX_FAILED_PIN_ATTEMPTS) {
                        writeResponse(
                            output,
                            429,
                            "text/html; charset=utf-8",
                            renderPage("", "Too many wrong PIN attempts. Close and reopen Phone Pairing on your TV."),
                        )
                    } else {
                        writeResponse(
                            output,
                            403,
                            "text/html; charset=utf-8",
                            renderPage("", "Invalid pairing PIN (${MAX_FAILED_PIN_ATTEMPTS - attempts} attempts remaining)."),
                        )
                    }
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

                if (!sessionConsumed.compareAndSet(false, true)) {
                    writeResponse(output, 410, "text/plain", "Pairing session already completed")
                    return
                }

                onCredentialsSubmitted(serverUrl, apiKey, allowUntrusted)
                writeResponse(output, 200, "text/html; charset=utf-8", renderSuccessPage(serverUrl))
            }

            else -> writeResponse(output, 404, "text/plain", "Not Found")
        }
    }

    private fun isExpectedHost(hostHeader: String): Boolean {
        val cleaned = hostHeader.trim().lowercase()
        val expected = "$boundHostDisplay:$boundPort".lowercase()
        return cleaned == expected ||
            cleaned == "127.0.0.1:$boundPort" ||
            cleaned == "localhost:$boundPort"
    }

    private fun readLineBounded(input: BufferedInputStream, maxBytes: Int, deadlineMs: Long): String? {
        val sb = StringBuilder()
        while (true) {
            if (System.currentTimeMillis() > deadlineMs) return null
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
                if (sb.length > maxBytes) return null
            }
        }
        return sb.toString()
    }

    private fun parseForm(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return buildMap {
            for (pair in raw.split('&')) {
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrNull() ?: continue
                    val value = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrNull() ?: continue
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
            408 -> "Request Timeout"
            410 -> "Gone"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
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
          <title>Slate TV · Sent</title>
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
            <p>Slate TV received your Immich credentials for <strong>${escapeHtml(serverUrl)}</strong>. Select <strong>Save &amp; Activate</strong> on your TV to finish.</p>
          </div>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val MAX_LINE_BYTES = 2048
        private const val MAX_HEADER_LINES = 64
        private const val MAX_TOTAL_HEADER_BYTES = 8 * 1024
        private const val MAX_CONCURRENT_CLIENTS = 4
        private const val REQUEST_DEADLINE_MS = 5_000L
        const val MAX_FAILED_PIN_ATTEMPTS = 5

        private val VIRTUAL_OR_VPN_PREFIXES = listOf("tun", "tap", "wg", "tailscale", "docker", "br-", "veth", "virbr")

        fun detectLocalIpv4(): String? {
            return runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var preferredLan: String? = null
                var fallbackLan: String? = null
                for (iface in interfaces.toList()) {
                    if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                    val name = iface.name.lowercase()
                    val isVpnOrVirtual = VIRTUAL_OR_VPN_PREFIXES.any { name.startsWith(it) }
                    for (addr in iface.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (!isVpnOrVirtual && (name.startsWith("eth") || name.startsWith("wlan") || name.startsWith("en"))) {
                                return ip
                            }
                            if (!isVpnOrVirtual && preferredLan == null) {
                                preferredLan = ip
                            } else if (fallbackLan == null) {
                                fallbackLan = ip
                            }
                        }
                    }
                }
                preferredLan ?: fallbackLan
            }.getOrNull()
        }
    }
}
