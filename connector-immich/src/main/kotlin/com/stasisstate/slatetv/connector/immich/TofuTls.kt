package com.stasisstate.slatetv.connector.immich

import android.annotation.SuppressLint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Trust-On-First-Use (TOFU) certificate pinning for self-hosted Immich servers with self-signed or
 * private-CA HTTPS certificates.
 *
 * Google Play safety: this file contains **no trust-all [X509TrustManager]**.
 * - Real traffic ([buildPinnedSsl]) uses the platform [TrustManagerFactory] initialised with an in-memory
 *   [KeyStore] that holds only the user-confirmed leaf certificate, plus a [HostnameVerifier] that requires
 *   both the expected host and byte-identical leaf certificate.
 * - The one-shot certificate probe ([probeLeafCertificate]) uses a trust manager whose check methods
 *   **always throw**; the captured leaf is returned to the UI only for explicit user confirmation and no
 *   application data is ever exchanged over the probe connection.
 */
object TofuTls {
    private const val PROBE_CONNECT_TIMEOUT_MS = 6_000
    private const val PROBE_READ_TIMEOUT_MS = 6_000

    /** Thrown unconditionally by the probe trust manager to carry the server's leaf certificate out of the handshake. */
    class CertCapturedException(val certificate: X509Certificate) :
        CertificateException("Server certificate captured for user confirmation")

    /** Material needed to configure an OkHttp client that trusts exactly one pinned leaf certificate. */
    class PinnedSsl(
        val sslSocketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
        val hostnameVerifier: HostnameVerifier,
    )

    /** A server leaf certificate captured by [probeLeafCertificate], shown to the user before pinning. */
    class ProbedCertificate(
        val host: String,
        val port: Int,
        val der: ByteArray,
    ) {
        private val cert: X509Certificate = parseCertificate(der)
        val sha256Hex: String = sha256Hex(der)
        val formattedFingerprint: String get() = formatFingerprint(sha256Hex)
        val subjectCn: String get() = commonName(cert.subjectX500Principal)
        val issuerCn: String get() = commonName(cert.issuerX500Principal)
        val notBefore: Date get() = cert.notBefore
        val notAfter: Date get() = cert.notAfter
        val isSelfSigned: Boolean get() = cert.subjectX500Principal == cert.issuerX500Principal
        val derBase64: String get() = Base64.getEncoder().encodeToString(der)
    }

    // ── Pinned runtime verification ───────────────────────────────────────────────────

    /**
     * Builds TLS material that trusts **only** [leafCertDer] for [expectedHost], using the platform's standard
     * [TrustManagerFactory] (no custom trust manager class). The returned [PinnedSsl.hostnameVerifier] accepts
     * a connection only when the hostname equals [expectedHost] (case-insensitive) AND the peer's leaf
     * certificate bytes are identical to [leafCertDer].
     */
    fun buildPinnedSsl(leafCertDer: ByteArray, expectedHost: String): PinnedSsl {
        val pinned = leafCertDer.copyOf()
        val certificate = parseCertificate(pinned)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("immich-pinned-leaf", certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw IllegalStateException("Platform TrustManagerFactory returned no single X509TrustManager")
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        val verifier = HostnameVerifier { hostname, session ->
            try {
                if (hostname == null || !hostname.equals(expectedHost, ignoreCase = true)) return@HostnameVerifier false
                val leaf = session?.peerCertificates?.firstOrNull() ?: return@HostnameVerifier false
                MessageDigest.isEqual(leaf.encoded, pinned)
            } catch (_: Exception) {
                false
            }
        }
        return PinnedSsl(sslContext.socketFactory, trustManager, verifier)
    }

    // ── One-shot certificate probe ────────────────────────────────────────────────────

    /** Probes the leaf certificate of an `https://` URL. Returns null for non-https or unparsable URLs. */
    @Throws(IOException::class)
    fun probeLeafCertificate(url: String): ProbedCertificate? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        return probeLeafCertificate(parsed.host, parsed.port)
    }

    /**
     * Opens a TLS connection to [host]:[port] with a trust manager that ALWAYS throws [CertCapturedException]
     * from `checkServerTrusted`, so the handshake can never complete and no request is ever sent. The captured
     * leaf certificate is returned for user confirmation.
     *
     * @throws IOException if the host is unreachable or no certificate could be captured.
     */
    @Throws(IOException::class)
    fun probeLeafCertificate(host: String, port: Int): ProbedCertificate {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(CapturingProbeTrustManager()), SecureRandom())
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = PROBE_READ_TIMEOUT_MS
            if (!isIpLiteral(host)) {
                runCatching {
                    socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                }
            }
            socket.connect(InetSocketAddress(host, port), PROBE_CONNECT_TIMEOUT_MS)
            try {
                socket.startHandshake()
            } catch (e: Exception) {
                val captured = findCause<CertCapturedException>(e)
                    ?: throw (e as? IOException ?: IOException("TLS probe failed", e))
                return ProbedCertificate(host = host, port = port, der = captured.certificate.encoded)
            }
            // Unreachable by construction: the probe trust manager never returns normally.
            throw IOException("TLS probe handshake unexpectedly completed")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Probe-only trust manager. Every check method throws, so it can never authorise a connection.
     * [checkServerTrusted] throws [CertCapturedException] carrying the leaf certificate.
     */
    @SuppressLint("CustomX509TrustManager")
    internal class CapturingProbeTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Probe trust manager never trusts clients")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Server presented no certificate")
            throw CertCapturedException(leaf)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ── Error classification ──────────────────────────────────────────────────────────

    /**
     * True when [error] means the server's certificate is not trusted (system trust failure, pinned certificate
     * mismatch, or hostname verification failure), i.e. the user may be offered TOFU pinning.
     */
    fun isUntrustedCertificateError(error: Throwable): Boolean {
        if (error is SSLPeerUnverifiedException) return true
        if (findCause<CertCapturedException>(error) != null) return true
        if (error !is SSLHandshakeException && findCause<SSLHandshakeException>(error) == null) return false
        return findCause<CertPathValidatorException>(error) != null ||
            findCause<CertificateException>(error) != null ||
            error.message.orEmpty().contains("certification path", ignoreCase = true) ||
            error.message.orEmpty().contains("trust anchor", ignoreCase = true)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Formats a hex digest as `AA:BB:CC:...`. */
    fun formatFingerprint(hex: String): String = hex.uppercase(Locale.US).chunked(2).joinToString(":")

    fun decodeBase64(value: String): ByteArray? =
        value.takeIf { it.isNotBlank() }?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }

    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun formatDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(date)

    internal fun commonName(principal: X500Principal): String {
        val name = principal.getName(X500Principal.RFC2253)
        val cn = Regex("""(?:^|,)\s*CN=((?:\\.|[^,])*)""", RegexOption.IGNORE_CASE).find(name)
            ?.groupValues?.get(1)?.replace("\\", "")
        return cn?.takeIf { it.isNotBlank() } ?: name.ifBlank { "(none)" }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)

    internal inline fun <reified T : Throwable> findCause(error: Throwable): T? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 16) {
            if (current is T) return current
            current.suppressed.firstOrNull { it is T }?.let { return it as T }
            current = current.cause
            depth++
        }
        return null
    }
}
