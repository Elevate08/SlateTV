package com.stasisstate.slatetv

import android.annotation.SuppressLint
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

object NetworkClientFactory {
    suspend fun OkHttpClient.executeCancellable(request: Request): Response {
        val call = newCall(request)
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
     * Builds an OkHttpClient that strips caller-supplied headers
     * on any cross-origin HTTP redirect so credentials can never leak to third-party hosts.
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
            .addInterceptor { chain ->
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
                    response.close()
                    val nextBuilder = currentReq.newBuilder().url(nextUrl)
                    if (!sameOrigin) {
                        currentReq.headers.names().forEach(nextBuilder::removeHeader)
                    }
                    currentReq = nextBuilder.build()
                    response = chain.proceed(currentReq)
                    redirectCount++
                }
                response
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
                // Restrict hostname bypass strictly to the explicitly configured host
                allowedHost != null && hostname.equals(allowedHost, ignoreCase = true)
            }
        }
        return builder.build()
    }
}
