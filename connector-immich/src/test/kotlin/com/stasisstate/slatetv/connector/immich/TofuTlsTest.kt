package com.stasisstate.slatetv.connector.immich

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TofuTlsTest {
    companion object {
        private lateinit var serverCert: TestServerCert
        private lateinit var otherCert: TestServerCert

        @BeforeClass
        @JvmStatic
        fun generateCerts() {
            serverCert = TestServerCert.generate("immich-test")
            otherCert = TestServerCert.generate("attacker")
        }
    }

    private fun httpsServer(): MockWebServer = MockWebServer().apply {
        useHttps(serverCert.serverSocketFactory, false)
        start()
    }

    private fun pinnedClient(der: ByteArray, host: String): OkHttpClient {
        val pinned = TofuTls.buildPinnedSsl(der, host)
        return OkHttpClient.Builder()
            .sslSocketFactory(pinned.sslSocketFactory, pinned.trustManager)
            .hostnameVerifier(pinned.hostnameVerifier)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Test
    fun `buildPinnedSsl accepts the matching self-signed certificate`() {
        val server = httpsServer()
        try {
            server.enqueue(MockResponse().setBody("pong"))
            val url = server.url("/api/server/ping")
            pinnedClient(serverCert.der, url.host).newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("pong", it.body!!.string())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `buildPinnedSsl uses the platform TrustManagerFactory trust manager not a custom class`() {
        val pinned = TofuTls.buildPinnedSsl(serverCert.der, "localhost")
        val platformClass = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as java.security.KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single().javaClass
        assertSame(platformClass, pinned.trustManager.javaClass)
        assertFalse(pinned.trustManager.javaClass.name.startsWith("com.stasisstate"))
        // The pinned trust manager rejects any other certificate outright.
        try {
            pinned.trustManager.checkServerTrusted(arrayOf(otherCert.certificate), "ECDHE_ECDSA")
            fail("A different certificate must be rejected")
        } catch (_: CertificateException) {
        }
        pinned.trustManager.checkServerTrusted(arrayOf(serverCert.certificate), "ECDHE_ECDSA")
    }

    @Test
    fun `buildPinnedSsl rejects a different certificate`() {
        val server = httpsServer()
        try {
            server.enqueue(MockResponse().setBody("secret"))
            val url = server.url("/api/albums")
            try {
                pinnedClient(otherCert.der, url.host).newCall(Request.Builder().url(url).build()).execute().close()
                fail("Handshake must fail when the pinned certificate does not match")
            } catch (e: SSLHandshakeException) {
                assertTrue(TofuTls.isUntrustedCertificateError(e))
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `buildPinnedSsl rejects the right certificate for the wrong expected host`() {
        val server = httpsServer()
        try {
            server.enqueue(MockResponse().setBody("secret"))
            val url = server.url("/api/albums")
            try {
                pinnedClient(serverCert.der, "photos.example.com").newCall(Request.Builder().url(url).build()).execute().close()
                fail("Hostname verification must fail for an unexpected host")
            } catch (e: SSLPeerUnverifiedException) {
                assertTrue(TofuTls.isUntrustedCertificateError(e))
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pinned hostname verifier returns false when the session throws`() {
        val pinned = TofuTls.buildPinnedSsl(serverCert.der, "localhost")
        val throwingSession = Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { _, method, _ ->
            if (method.name == "getPeerCertificates") throw SSLPeerUnverifiedException("no peer")
            null
        } as SSLSession
        assertFalse(pinned.hostnameVerifier.verify("localhost", throwingSession))
        assertFalse(pinned.hostnameVerifier.verify("localhost", null))
        assertFalse(pinned.hostnameVerifier.verify(null, throwingSession))
    }

    @Test
    fun `system trust rejects the self-signed server so TOFU is required`() {
        val server = httpsServer()
        try {
            server.enqueue(MockResponse().setBody("x"))
            try {
                OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().close()
                fail("System trust must reject a self-signed certificate")
            } catch (e: IOException) {
                assertTrue("Expected untrusted-certificate classification for $e", TofuTls.isUntrustedCertificateError(e))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `probeLeafCertificate extracts the exact SHA-256 fingerprint and sends no request`() {
        val server = httpsServer()
        try {
            val url = server.url("/")
            val probed = TofuTls.probeLeafCertificate(url.host, url.port)
            val expectedHex = TofuTls.sha256Hex(serverCert.der)
            assertEquals(expectedHex, probed.sha256Hex)
            assertEquals(64, probed.sha256Hex.length)
            assertTrue(probed.der.contentEquals(serverCert.der))
            assertEquals(TofuTls.formatFingerprint(expectedHex), probed.formattedFingerprint)
            assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(probed.formattedFingerprint))
            assertEquals("immich-test", probed.subjectCn)
            assertEquals("immich-test", probed.issuerCn)
            assertTrue(probed.isSelfSigned)
            assertTrue(probed.notAfter.after(probed.notBefore))
            assertEquals(url.host, probed.host)
            assertEquals(url.port, probed.port)
            assertEquals(0, server.requestCount)

            // URL overload resolves host & port; pinning the probed bytes then succeeds.
            val viaUrl = TofuTls.probeLeafCertificate(url.toString())
            assertNotNull(viaUrl)
            assertEquals(expectedHex, viaUrl!!.sha256Hex)
            server.enqueue(MockResponse().setBody("ok"))
            pinnedClient(viaUrl.der, url.host).newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `probeLeafCertificate returns null for http and fails cleanly for unreachable hosts`() {
        assertNull(TofuTls.probeLeafCertificate("http://192.168.1.10:2283"))
        assertNull(TofuTls.probeLeafCertificate("not a url"))
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            TofuTls.probeLeafCertificate("127.0.0.1", closedPort)
            fail("Expected IOException for a closed port")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `probe trust manager never returns from checkServerTrusted or checkClientTrusted`() {
        val tm = TofuTls.CapturingProbeTrustManager()
        val chain = arrayOf(serverCert.certificate)
        try {
            tm.checkServerTrusted(chain, "ECDHE_ECDSA")
            fail("checkServerTrusted must always throw")
        } catch (e: TofuTls.CertCapturedException) {
            assertSame(serverCert.certificate, e.certificate)
        }
        for (bad in listOf<Array<X509Certificate>?>(null, emptyArray())) {
            try {
                tm.checkServerTrusted(bad, "RSA")
                fail("checkServerTrusted must throw for $bad")
            } catch (e: CertificateException) {
                assertFalse(e is TofuTls.CertCapturedException)
            }
        }
        for (bad in listOf<Array<X509Certificate>?>(chain, null, emptyArray())) {
            try {
                tm.checkClientTrusted(bad, "RSA")
                fail("checkClientTrusted must always throw")
            } catch (_: CertificateException) {
            }
        }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun `module sources contain zero trust-all X509TrustManager implementations`() {
        val mainDir = File("src/main")
        assertTrue("Run from the module directory: ${File(".").absolutePath}", mainDir.isDirectory)
        val sources = mainDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList()
        assertTrue(sources.isNotEmpty())
        val implementors = mutableListOf<String>()
        for (file in sources) {
            val text = file.readText()
            assertFalse("${file.name} must not suppress TrustAllX509TrustManager", text.contains("TrustAllX509TrustManager"))
            assertFalse(
                "${file.name} must not enable or accept untrusted TLS",
                Regex("""allowUntrustedTls\s*(=\s*true|:)""").containsMatchIn(text),
            )
            assertFalse(
                "${file.name} has an empty checkServerTrusted",
                Regex("""checkServerTrusted\([^)]*\)\s*(=\s*Unit|\{\s*\})""").containsMatchIn(text),
            )
            assertFalse(
                "${file.name} has an empty checkClientTrusted",
                Regex("""checkClientTrusted\([^)]*\)\s*(=\s*Unit|\{\s*\})""").containsMatchIn(text),
            )
            Regex("""class\s+\w+[^{\n]*:\s*X509(Extended)?TrustManager\b""").findAll(text).forEach { implementors += file.name }
            Regex("""object\s*:\s*X509(Extended)?TrustManager""").findAll(text).forEach { implementors += "${file.name}(anonymous)" }
        }
        assertEquals("Only the always-throwing probe may implement X509TrustManager", listOf("TofuTls.kt"), implementors)

        val tofu = sources.single { it.name == "TofuTls.kt" }.readText()
        val body = tofu.substringAfter("override fun checkServerTrusted").substringBefore("override fun getAcceptedIssuers")
        assertTrue(body.contains("throw CertCapturedException(leaf)"))
        assertFalse(body.contains("return"))
    }

    @Test
    fun `fingerprint formatting and common name parsing`() {
        assertEquals("AB:CD:EF", TofuTls.formatFingerprint("abcdef"))
        assertEquals("immich-test", TofuTls.commonName(serverCert.certificate.subjectX500Principal))
        assertTrue(TofuTls.decodeBase64(TofuTls.encodeBase64(serverCert.der))!!.contentEquals(serverCert.der))
        assertNull(TofuTls.decodeBase64(""))
        assertNull(TofuTls.decodeBase64("!!!not base64"))
        assertFalse(TofuTls.isUntrustedCertificateError(IOException("connection refused")))
        assertFalse(TofuTls.isUntrustedCertificateError(SSLHandshakeException("protocol_version")))
    }
}
