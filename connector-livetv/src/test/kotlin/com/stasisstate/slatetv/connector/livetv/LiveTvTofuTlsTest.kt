package com.stasisstate.slatetv.connector.livetv

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession

class LiveTvTofuTlsTest {
    private class Fixture(val context: SSLContext, val leaf: X509Certificate)

    private fun fixture(name: String): Fixture {
        val store = KeyStore.getInstance("PKCS12")
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)).use { store.load(it, "changeit".toCharArray()) }
        val leaf = store.getCertificate(store.aliases().toList().single()) as X509Certificate
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        return Fixture(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }, leaf)
    }

    private val certA by lazy { fixture("tofu-a.p12") }
    private val certB by lazy { fixture("tofu-b.p12") }

    private fun httpsServer(cert: Fixture) = MockWebServer().apply { useHttps(cert.context.socketFactory, false) }

    private fun MockWebServer.serverUrl() = "https://localhost:$port/"

    private fun pin(server: MockWebServer, cert: Fixture) = TofuPin("https://localhost:${server.port}", cert.leaf.encoded)

    private fun config(server: MockWebServer, cert: Fixture?) = LiveTvConfig(server.serverUrl(), "test-token")
        .let { if (cert != null) it.withPin(pin(server, cert)) else it }

    @Test fun probeThrowsCertCapturedExceptionCarryingTheLeaf() {
        val manager = CertCaptureTrustManager()
        val error = assertThrows(CertCapturedException::class.java) {
            manager.checkServerTrusted(arrayOf(certA.leaf, certB.leaf), "RSA")
        }
        assertEquals(certA.leaf, error.certificate)
        assertThrows(CertificateException::class.java) { manager.checkClientTrusted(arrayOf(certA.leaf), "RSA") }
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(emptyArray(), "RSA") }
        assertEquals(0, manager.acceptedIssuers.size)
    }

    @Test fun probeReadsLeafFromAnUntrustedServerAndNeverCompletesTheHandshake() = httpsServer(certA).use { server ->
        server.enqueue(MockResponse().setBody("must never be served"))
        val captured = LiveTvTofuTls.probe("localhost", server.port, timeoutMs = 5_000)
        assertNotNull(captured)
        assertArrayEquals(certA.leaf.encoded, captured!!.encoded)
        assertEquals(0, server.requestCount)
    }

    @Test fun probeRefusesNonLanAddresses() = httpsServer(certA).use { server ->
        assertNull(LiveTvTofuTls.probe("localhost", server.port, timeoutMs = 2_000, lanOnly = { false }))
    }

    @Test fun capturedCertificateIsFoundThroughWrappedCauseChain() {
        val wrapped = SSLHandshakeException("handshake").initCause(CertCapturedException(certB.leaf))
        assertEquals(certB.leaf, LiveTvTofuTls.capturedCertificate(wrapped))
        assertNull(LiveTvTofuTls.capturedCertificate(IOException("plain")))
        assertTrue(LiveTvTofuTls.isTrustFailure(wrapped))
        assertFalse(LiveTvTofuTls.isTrustFailure(IOException("timeout")))
    }

    @Test fun fingerprintIsColonSeparatedUppercaseSha256() {
        val fingerprint = LiveTvTofuTls.sha256Fingerprint(certA.leaf.encoded)
        assertTrue(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(fingerprint))
    }

    @Test fun unpinnedClientRejectsSelfSignedServer() = httpsServer(certA).use { server ->
        server.enqueue(MockResponse().setBody("{}"))
        val error = assertThrows(Exception::class.java) { runBlocking { JellyfinApiClient(config(server, null)).serverName() } }
        assertTrue(LiveTvTofuTls.isTrustFailure(error))
        assertEquals(0, server.requestCount)
        assertNotNull(LiveTvTofuTls.defaultTrustError(parseServerUrl(server.serverUrl())!!))
    }

    @Test fun pinnedClientAcceptsTheExactPinnedCertificate() = httpsServer(certA).use { server ->
        server.enqueue(MockResponse().setBody("""{"ServerName":"Pinned"}"""))
        assertEquals("Pinned", runBlocking { JellyfinApiClient(config(server, certA)).serverName() })
        assertEquals(1, server.requestCount)
    }

    @Test fun pinnedClientRejectsADifferentCertificate() = httpsServer(certB).use { server ->
        server.enqueue(MockResponse().setBody("""{"ServerName":"Imposter"}"""))
        val error = assertThrows(Exception::class.java) {
            runBlocking { JellyfinApiClient(config(server, certA)).serverName() }
        }
        assertTrue(error.toString(), LiveTvTofuTls.isTrustFailure(error))
        assertEquals(0, server.requestCount)
    }

    private fun session(leaf: X509Certificate?) = java.lang.reflect.Proxy.newProxyInstance(
        SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java),
    ) { _, method, _ ->
        if (method.name == "getPeerCertificates") {
            leaf?.let { arrayOf<java.security.cert.Certificate>(it) } ?: throw javax.net.ssl.SSLPeerUnverifiedException("none")
        } else throw UnsupportedOperationException(method.name)
    } as SSLSession

    @Test fun peerVerificationRequiresConfiguredHostAndByteIdenticalLeaf() {
        val der = certA.leaf.encoded
        assertTrue(LiveTvTofuTls.verifyPinnedPeer("nas.local", "nas.local", session(certA.leaf), der))
        assertTrue(LiveTvTofuTls.verifyPinnedPeer("NAS.local", "nas.local", session(certA.leaf), der))
        assertFalse(LiveTvTofuTls.verifyPinnedPeer("evil.local", "nas.local", session(certA.leaf), der))
        assertFalse(LiveTvTofuTls.verifyPinnedPeer("nas.local", "nas.local", session(certB.leaf), der))
        assertFalse(LiveTvTofuTls.verifyPinnedPeer("nas.local", "nas.local", session(null), der))
    }

    @Test fun pinIsBoundToServerOriginAndDroppedWhenUrlChanges() = httpsServer(certA).use { server ->
        val config = config(server, certA)
        assertEquals(pin(server, certA), config.activePin())
        val moved = config.copy(serverUrl = "https://other.example:${server.port}/")
        assertNull(moved.activePin())
        assertEquals("", moved.normalized().pinnedCertDer)
        assertNull(config.copy(serverUrl = "http://localhost:${server.port}/").activePin())
        assertNotEquals(config, config.withPin(null))
        assertNotEquals(logoNamespace(config, "u"), logoNamespace(config.withPin(null), "u"))
    }

    @Test fun quickConnectHonoursPin() = httpsServer(certA).use { server ->
        server.enqueue(MockResponse().setBody("""{"Secret":"s","Code":"123456"}"""))
        val session = runBlocking { JellyfinApiClient.initiateQuickConnect(server.serverUrl(), pin(server, certA)) }
        assertEquals("123456", session.code)
        assertThrows(Exception::class.java) { runBlocking { JellyfinApiClient.initiateQuickConnect(server.serverUrl(), null) } }
        Unit
    }
}
