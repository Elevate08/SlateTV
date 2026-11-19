package com.stasisstate.slatetv.connector.livetv

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

internal class PairingSession(val pin: String = String.format(Locale.ROOT, "%06d", SecureRandom().nextInt(1_000_000))) {
    private var failures = 0
    private var consumed = false

    @Synchronized
    fun status(): Int = when {
        consumed -> 410
        failures >= 5 -> 429
        else -> 200
    }

    @Synchronized
    fun submit(candidate: String, valid: Boolean): Int {
        if (status() != 200) return status()
        if (candidate != pin) return if (++failures >= 5) 429 else 403
        if (!valid) return 400
        consumed = true
        return 200
    }
}

internal class LanPairingServer(
    address: InetAddress,
    private val onSubmit: (String, String, String) -> Unit,
) : Closeable {
    private val session = PairingSession()
    val pin get() = session.pin
    private val listener = ServerSocket(0, 4, address)
    private val authority = "${address.hostAddress}:${listener.localPort}"
    val url = "http://$authority/"
    private var connection: Socket? = null

    init {
        thread(name = "Jellyfin pairing", isDaemon = true) {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        synchronized(this) {
                            if (listener.isClosed) return@thread
                            connection = socket
                        }
                        val credentials = runCatching { handle(socket) }.getOrNull()
                        synchronized(this) { connection = null }
                        if (credentials != null) {
                            close()
                            onSubmit(credentials.first, credentials.second, credentials.third)
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing the listener interrupts accept.
            } finally {
                close()
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { listener.close() }
        runCatching { connection?.close() }
        connection = null
    }

    private fun handle(socket: Socket): Triple<String, String, String>? {
        val deadline = System.nanoTime() + 5_000_000_000L
        val input = BufferedInputStream(socket.getInputStream())
        fun readByte(): Int {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw IOException("Pairing request expired")
            socket.soTimeout = remaining.toInt()
            return input.read().also { if (it == -1) throw IOException("Incomplete pairing request") }
        }
        fun line(): String = buildString {
            while (length < 2048) {
                val byte = readByte()
                if (byte == 13) {
                    if (readByte() != 10) throw IOException("Invalid header")
                    return@buildString
                }
                if (byte !in 32..126) throw IOException("Invalid header")
                append(byte.toChar())
            }
            throw IOException("Pairing header too long")
        }
        val request = line()
        val headers = mutableMapOf<String, String>()
        var bytes = request.length
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            bytes += header.length + 2
            if (headers.size >= 32 || bytes > 8192) return respond(socket, 431)
            val name = header.substringBefore(':').lowercase(Locale.ROOT)
            if (':' !in header || headers.put(name, header.substringAfter(':').trim()) != null) return respond(socket, 400)
        }
        if (headers["host"] != authority || "transfer-encoding" in headers) return respond(socket, 403)
        val status = session.status()
        if (status != 200) return respond(socket, status)
        if (request == "GET / HTTP/1.1") return respond(socket, 200, FORM)
        if (request != "POST /pair HTTP/1.1") return respond(socket, 404)
        if (headers["origin"] != null && headers["origin"] != "http://$authority") return respond(socket, 403)
        if (headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded") return respond(socket, 400)
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..8192 } ?: return respond(socket, 400)
        val form = ByteArray(length) { readByte().toByte() }.decodeToString().split('&').associate { field ->
            field.substringBefore('=') to URLDecoder.decode(field.substringAfter('=', ""), "UTF-8")
        }
        val server = parseServerUrl(form["server"].orEmpty())
        val key = form["key"].orEmpty().trim()
        val user = form["user"].orEmpty().trim()
        val result = session.submit(form["pin"].orEmpty(), server != null && validApiKey(key) && user.length <= 256)
        if (result != 200) return respond(socket, result)
        // Deliver a consumed submission even if the phone disconnects before reading the response.
        runCatching { respond(socket, 200, "<h1>Sent to TV</h1><p>Select Save on your TV.</p>") }
        return Triple(server.toString(), key, user)
    }

    private fun respond(socket: Socket, status: Int, html: String? = null): Nothing? {
        val message = when (status) {
            200 -> "OK"
            400 -> "Invalid pairing details"
            403 -> "Forbidden or incorrect PIN"
            404 -> "Not found"
            410 -> "Pairing already completed"
            429 -> "Too many PIN attempts. Reopen pairing on your TV."
            else -> "Headers too large"
        }
        val body = (html ?: "<h1>$message</h1><p><a href='/'>Return to pairing</a></p>").toByteArray()
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 $status $message\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'none'; form-action 'self'; frame-ancestors 'none'\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n").toByteArray())
        output.write(body)
        output.flush()
        return null
    }

    private companion object {
        const val FORM = """<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Jellyfin TV pairing</title><h1>Pair Jellyfin with Slate TV</h1>
            <form method="post" action="/pair" autocomplete="off">
            <p><label>TV PIN <input name="pin" required inputmode="numeric" pattern="[0-9]{6}"></label></p>
            <p><label>Jellyfin server URL <input name="server" required type="url"></label></p>
            <p><label>API key <input name="key" required type="password"></label></p>
            <p><label>User ID (optional) <input name="user"></label></p>
            <button>Send to TV</button></form>"""
    }
}
