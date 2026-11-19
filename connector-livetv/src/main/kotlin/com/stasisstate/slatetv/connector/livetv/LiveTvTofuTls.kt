package com.stasisstate.slatetv.connector.livetv

import android.annotation.SuppressLint
import okhttp3.HttpUrl
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Thrown by [CertCaptureTrustManager] to carry the server's leaf certificate out of a TLS handshake. */
class CertCapturedException(val certificate: X509Certificate) : CertificateException("Server certificate captured for review.")

/**
 * Trust manager used ONLY to read a server's certificate before the user decides whether to pin it.
 * It never trusts anything: [checkServerTrusted] always throws, so no connection made with it can ever
 * complete a handshake or carry application data.
 */
@SuppressLint("CustomX509TrustManager") // Rejects every certificate by design; it only captures the leaf.
internal class CertCaptureTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client authentication is not supported.")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Server sent no certificate.")
        throw CertCapturedException(leaf)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal class PinnedTls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

/** Trust-on-first-use helpers: probe an untrusted server for its certificate, then verify against a user-approved pin. */
object LiveTvTofuTls {
    /** `AB:CD:…` SHA-256 fingerprint of a DER certificate, for display. */
    fun sha256Fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    /** Finds a [CertCapturedException] anywhere in [error]'s cause chain (handshakes wrap it in SSLHandshakeException). */
    fun capturedCertificate(error: Throwable?): X509Certificate? {
        var current = error
        var depth = 0
        while (current != null && depth++ < 16) {
            if (current is CertCapturedException) return current.certificate
            current = current.cause
        }
        return null
    }

    /** True when [error] looks like a certificate-trust failure that pinning could resolve. */
    fun isTrustFailure(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth++ < 16) {
            if (current is SSLException || current is CertificateException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Connects to [base] with normal system trust (no pin). Returns `null` when the handshake succeeds,
     * otherwise the failure. Pinning is only offered after this fails with a certificate-trust error.
     */
    fun defaultTrustError(base: HttpUrl): Throwable? = try {
        buildJellyfinHttpClient(base, null).newCall(Request.Builder().url(base).head().build()).execute().close()
        null
    } catch (e: Exception) {
        e
    }

    /**
     * Opens a TLS connection to [host]:[port] using [CertCaptureTrustManager] and returns the server's leaf
     * certificate. The handshake is always aborted by the trust manager. Only LAN / private-mesh addresses are
     * probed (via [lanOnly]); returns `null` if the server cannot be reached or presents no certificate.
     */
    fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = 5_000,
        lanOnly: (InetAddress) -> Boolean = InetAddress::isLanOrTailscale,
    ): X509Certificate? {
        val address = runCatching { InetAddress.getAllByName(host).firstOrNull(lanOnly) }.getOrNull() ?: return null
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(CertCaptureTrustManager()), null) }
        val raw = Socket()
        return try {
            raw.connect(InetSocketAddress(address, port), timeoutMs)
            raw.soTimeout = timeoutMs
            (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { it.startHandshake() }
            null
        } catch (e: Exception) {
            capturedCertificate(e)
        } finally {
            runCatching { raw.close() }
        }
    }

    /** Standard platform trust manager initialised with an in-memory store holding ONLY the pinned leaf certificate. */
    internal fun pinnedTls(certDer: ByteArray): PinnedTls {
        val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(certDer.inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("pinned-leaf", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
        return PinnedTls(context.socketFactory, trustManager)
    }

    /** Requires the requested host to be the configured host and the session's leaf to be byte-identical to the pin. */
    fun verifyPinnedPeer(requestedHost: String, configuredHost: String, session: SSLSession, certDer: ByteArray): Boolean {
        if (!requestedHost.equals(configuredHost, ignoreCase = true)) return false
        val leaf: Certificate = runCatching { session.peerCertificates.firstOrNull() }.getOrNull() ?: return false
        return runCatching { leaf.encoded.contentEquals(certDer) }.getOrDefault(false)
    }
}
