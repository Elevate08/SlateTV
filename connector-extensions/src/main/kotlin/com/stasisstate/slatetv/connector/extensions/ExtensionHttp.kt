package com.stasisstate.slatetv.connector.extensions

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal data class FetchResult(val body: String, val finalUrl: HttpUrl)

/**
 * Hardened GET client: standard system TLS only, manual redirects, bounded bodies, and an
 * origin-locked auth header that is never forwarded to a different origin.
 */
internal class ExtensionHttp(base: OkHttpClient? = null) {
    private val secureClient: OkHttpClient
    private val lanClient: OkHttpClient

    init {
        val builder = (base ?: OkHttpClient()).newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
        secureClient = builder.build()
        lanClient = builder.dns(LanOnlyDns).build()
    }

    /**
     * Fetches [start]. [auth] is attached only while the request stays on the origin of [start];
     * after any cross-origin hop it is dropped for the rest of the chain.
     */
    fun get(
        start: HttpUrl,
        auth: List<Pair<String, String>> = emptyList(),
        maxBytes: Long = MAX_BODY_BYTES,
    ): FetchResult {
        var url = start
        var authAllowed = true
        var hops = 0
        while (true) {
            if (!ExtensionUrlPolicy.transportAllowed(url)) {
                throw ExtensionException("Cleartext HTTP is only allowed for local network addresses. Use HTTPS.")
            }
            val headers = Headers.Builder()
                .add("Accept", "application/json")
                .add("User-Agent", USER_AGENT)
                .apply { if (authAllowed) auth.forEach { (n, v) -> set(n, v) } }
                .build()
                .let { if (hops > 0) redirectHeaders(it) else it }
            val request = Request.Builder().url(url).headers(headers).get().build()
            val client = if (url.isHttps) secureClient else lanClient
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        if (++hops > MAX_REDIRECTS) throw ExtensionException("Too many redirects")
                        val location = response.header("Location") ?: throw ExtensionException("Invalid redirect")
                        val next = url.resolve(location) ?: throw ExtensionException("Invalid redirect")
                        if (url.isHttps && !next.isHttps) throw ExtensionException("Redirect downgrades HTTPS to HTTP")
                        if (!ExtensionUrlPolicy.sameOrigin(start, next)) authAllowed = false
                        url = next
                        return@use
                    }
                    if (!response.isSuccessful) {
                        throw ExtensionException(
                            when (response.code) {
                                401, 403 -> "The server rejected the request (HTTP ${response.code}). An API key may be required."
                                404 -> "Not found (HTTP 404)"
                                else -> "Server returned HTTP ${response.code}"
                            },
                            response.code,
                        )
                    }
                    val body = response.body ?: throw ExtensionException("Empty response")
                    if (body.contentLength() > maxBytes) throw ExtensionException("Response is larger than 1 MiB")
                    val source = body.source()
                    val buffer = Buffer()
                    while (true) {
                        if (source.read(buffer, 8192) == -1L) break
                        if (buffer.size > maxBytes) throw ExtensionException("Response is larger than 1 MiB")
                    }
                    return FetchResult(buffer.readUtf8(), url)
                }
            } catch (e: ExtensionException) {
                throw e
            } catch (e: IOException) {
                throw ExtensionException(friendly(e), cause = e)
            }
        }
    }

    internal companion object {
        const val MAX_BODY_BYTES = 1L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "SlateTV-Extensions/1"

        /** Request headers to keep when following a redirect: body headers never survive one. */
        fun redirectHeaders(headers: Headers): Headers = headers.newBuilder()
            .removeAll("Content-Type")
            .removeAll("Content-Length")
            .removeAll("Content-Encoding")
            .removeAll("Transfer-Encoding")
            .build()

        private fun friendly(e: IOException): String = when (e) {
            is UnknownHostException -> "Host not found" + (e.message?.takeIf { "local network" in it }?.let { ". $it" } ?: "")
            is SSLException -> "Secure connection failed (certificate not trusted)"
            is SocketTimeoutException -> "The server took too long to respond"
            else -> "Could not connect to the server"
        }
    }
}
