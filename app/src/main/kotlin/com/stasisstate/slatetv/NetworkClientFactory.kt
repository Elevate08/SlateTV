package com.stasisstate.slatetv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
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
     * Builds the host OkHttpClient with strict system TLS verification and origin-bound redirects:
     * any redirect changing scheme, host, or port strips all caller-supplied headers so tokens
     * can never leak to third-party origins, and HTTP 303 switches to GET per RFC 9110.
     */
    fun defaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
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
                    val code = response.code
                    response.close()
                    val nextBuilder = currentReq.newBuilder().url(nextUrl)
                    if (code == 303 && currentReq.method != "GET" && currentReq.method != "HEAD") {
                        nextBuilder.method("GET", null)
                    }
                    if (!sameOrigin) {
                        currentReq.headers.names().forEach(nextBuilder::removeHeader)
                    }
                    currentReq = nextBuilder.build()
                    response = chain.proceed(currentReq)
                    redirectCount++
                }
                response
            }
            .build()
    }
}
