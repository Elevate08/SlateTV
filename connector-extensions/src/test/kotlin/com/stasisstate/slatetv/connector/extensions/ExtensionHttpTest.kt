package com.stasisstate.slatetv.connector.extensions

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionHttpTest {
    private val servers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()
    private val http = ExtensionHttp()

    @After fun tearDown() = servers.forEach { it.shutdown() }

    private fun server() = loopbackServer().also { servers += it }

    private fun failure(block: () -> Unit): ExtensionException {
        try {
            block()
        } catch (e: ExtensionException) {
            return e
        }
        fail("expected ExtensionException")
        throw IllegalStateException()
    }

    @Test fun sendsAuthHeaderOnSameOrigin() {
        val s = server()
        s.enqueue(json("{}"))
        http.get(s.base(), listOf("X-API-Key" to "k1"))
        val req = s.takeRequest()
        assertEquals("k1", req.getHeader("X-API-Key"))
        assertEquals("application/json", req.getHeader("Accept"))
    }

    @Test fun sameOriginRedirectKeepsAuthHeader() {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        s.enqueue(json("{\"ok\":true}"))
        val result = http.get(s.base(), listOf("X-API-Key" to "k1"))
        assertEquals("{\"ok\":true}", result.body)
        s.takeRequest()
        assertEquals("k1", s.takeRequest().getHeader("X-API-Key"))
    }

    @Test fun crossOriginRedirectStripsAuthHeader() {
        val origin = server()
        val other = server()
        other.enqueue(json("{}"))
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.base().resolve("/x").toString()))
        http.get(origin.base(), listOf("X-API-Key" to "k1", "Authorization" to "Bearer k1"))
        assertEquals("k1", origin.takeRequest().getHeader("X-API-Key"))
        val forwarded = other.takeRequest()
        assertNull(forwarded.getHeader("X-API-Key"))
        assertNull(forwarded.getHeader("Authorization"))
    }

    @Test fun authStaysStrippedAfterReturningToOrigin() {
        val a = server()
        val b = server()
        b.enqueue(MockResponse().setResponseCode(307).setHeader("Location", a.base().resolve("/back").toString()))
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", b.base().resolve("/hop").toString()))
        a.enqueue(json("{}"))
        http.get(a.base(), listOf("X-API-Key" to "k1"))
        assertEquals("k1", a.takeRequest().getHeader("X-API-Key"))
        assertNull(b.takeRequest().getHeader("X-API-Key"))
        assertNull(a.takeRequest().getHeader("X-API-Key"))
    }

    @Test fun redirectOn303UsesGetWithoutBodyHeaders() {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/see-other"))
        s.enqueue(json("{}"))
        http.get(s.base())
        s.takeRequest()
        val second = s.takeRequest()
        assertEquals("GET", second.method)
        assertNull(second.getHeader("Content-Type"))
        assertNull(second.getHeader("Content-Length"))
        assertNull(second.getHeader("Transfer-Encoding"))
    }

    @Test fun redirectHeadersDropBodyHeaders() {
        val original = Headers.headersOf(
            "Content-Type", "application/json", "Content-Length", "3", "Transfer-Encoding", "chunked",
            "Content-Encoding", "gzip", "Accept", "application/json",
        )
        val stripped = ExtensionHttp.redirectHeaders(original)
        assertEquals(listOf("Accept"), stripped.names().toList())
    }

    @Test fun redirectToPublicCleartextRejectedWithoutConnecting() {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/steal"))
        val e = failure { http.get(s.base(), listOf("X-API-Key" to "k1")) }
        assertTrue(e.message!!.contains("local network"))
        assertEquals(1, s.requestCount)
    }

    @Test fun redirectLoopStops() {
        val s = server()
        repeat(10) { s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        assertTrue(failure { http.get(s.base()) }.message!!.contains("redirect"))
    }

    @Test fun rejectsOversizedBodyAndHttpErrors() {
        val s = server()
        s.enqueue(json("x".repeat(1024 * 1024 + 1)))
        assertTrue(failure { http.get(s.base()) }.message!!.contains("1 MiB"))
        s.enqueue(json("x".repeat(1024 * 1024)))
        assertEquals(1024 * 1024, http.get(s.base()).body.length)
        s.enqueue(json("{}", 500))
        assertEquals(500, failure { http.get(s.base()) }.status)
    }

    @Test fun cleartextToPublicHostRejectedUpFront() {
        val e = failure { http.get("http://example.com/".toHttpUrl()) }
        assertFalse(e.message.isNullOrEmpty())
        assertTrue(e.message!!.contains("HTTPS"))
    }

    @Test fun lanOnlyDnsRejectsPublicAddress() {
        assertTrue(ExtensionUrlPolicy.isPrivateAddress(java.net.InetAddress.getByName("192.168.1.1")))
        assertFalse(ExtensionUrlPolicy.isPrivateAddress(java.net.InetAddress.getByName("8.8.8.8")))
    }
}
