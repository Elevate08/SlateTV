package com.stasisstate.slatetv.connector.immich

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/** Self-signed server identity generated with the JDK's `keytool` (no SAN, so only pinning can make it trusted). */
class TestServerCert(val certificate: X509Certificate, val serverSocketFactory: SSLSocketFactory) {
    val der: ByteArray get() = certificate.encoded

    companion object {
        private const val PASSWORD = "changeit"

        fun generate(cn: String = "immich-test"): TestServerCert {
            val dir = Files.createTempDirectory("tofu-cert").toFile()
            try {
                val store = File(dir, "server.p12")
                val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
                val process = ProcessBuilder(
                    keytool, "-genkeypair",
                    "-alias", "server",
                    "-keyalg", "EC",
                    "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA",
                    "-dname", "CN=$cn, O=Slate Test",
                    "-validity", "30",
                    "-storetype", "PKCS12",
                    "-keystore", store.absolutePath,
                    "-storepass", PASSWORD,
                    "-keypass", PASSWORD,
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }

                val ks = KeyStore.getInstance("PKCS12").apply {
                    store.inputStream().use { load(it, PASSWORD.toCharArray()) }
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(ks, PASSWORD.toCharArray())
                }
                val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
                return TestServerCert(ks.getCertificate("server") as X509Certificate, ctx.socketFactory)
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
