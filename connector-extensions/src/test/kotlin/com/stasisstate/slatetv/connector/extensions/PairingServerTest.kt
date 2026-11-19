package com.stasisstate.slatetv.connector.extensions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PairingServerTest {
    private var server: PairingServer? = null

    @After fun tearDown() { server?.close() }

    private fun start(received: AtomicReference<Pair<String, String?>?> = AtomicReference(), latch: CountDownLatch = CountDownLatch(1)): PairingServer =
        PairingServer(InetAddress.getByName("127.0.0.1")) { url, key -> received.set(url to key); latch.countDown() }.also { server = it }

    private fun request(s: PairingServer, raw: String): Int {
        val port = s.url.substringAfterLast(':').trimEnd('/').toInt()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            val first = socket.getInputStream().bufferedReader().readLine() ?: return -1
            return first.split(' ')[1].toInt()
        }
    }

    private fun post(s: PairingServer, body: String, host: String? = null): Int {
        val port = s.url.substringAfterLast(':').trimEnd('/')
        return request(
            s,
            "POST /pair HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:$port"}\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body",
        )
    }

    @Test fun pinIsSixDigits() {
        assertTrue(Regex("^\\d{6}$").matches(start().pin))
    }

    @Test fun servesFormOnlyForCorrectHost() {
        val s = start()
        val port = s.url.substringAfterLast(':').trimEnd('/')
        assertEquals(200, request(s, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"))
        assertEquals(403, request(s, "GET / HTTP/1.1\r\nHost: evil.example.com\r\n\r\n"))
        assertEquals(403, request(s, "GET / HTTP/1.1\r\nHost: 127.0.0.1:1\r\n\r\n"))
    }

    @Test fun wrongHostIsRejectedEvenWithCorrectPin() {
        val received = AtomicReference<Pair<String, String?>?>()
        val s = start(received)
        assertEquals(403, post(s, "pin=${s.pin}&manifest=https%3A%2F%2Fexample.com%2Fm.json", host = "attacker.example"))
        assertNull(received.get())
    }

    @Test fun correctPinDeliversOnceAndConsumesSession() {
        val received = AtomicReference<Pair<String, String?>?>()
        val latch = CountDownLatch(1)
        val s = start(received, latch)
        assertEquals(200, post(s, "pin=${s.pin}&manifest=https%3A%2F%2Fexample.com%2Fm.json&key=abc123"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("https://example.com/m.json" to "abc123", received.get())
    }

    @Test fun blankKeyBecomesNull() {
        val received = AtomicReference<Pair<String, String?>?>()
        val latch = CountDownLatch(1)
        val s = start(received, latch)
        assertEquals(200, post(s, "pin=${s.pin}&manifest=https%3A%2F%2Fexample.com%2Fm.json&key="))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNull(received.get()!!.second)
    }

    @Test fun wrongPinRejectedAndLocksAfterFiveAttempts() {
        val received = AtomicReference<Pair<String, String?>?>()
        val s = start(received)
        val bad = "pin=${if (s.pin == "000000") "111111" else "000000"}&manifest=https%3A%2F%2Fexample.com%2Fm.json"
        repeat(4) { assertEquals(403, post(s, bad)) }
        assertEquals(429, post(s, bad))
        assertEquals(429, post(s, "pin=${s.pin}&manifest=https%3A%2F%2Fexample.com%2Fm.json"))
        assertNull(received.get())
    }

    @Test fun invalidManifestUrlRejectedWithoutConsumingPin() {
        val s = start()
        assertEquals(400, post(s, "pin=${s.pin}&manifest=http%3A%2F%2Fexample.com%2Fm.json")) // public cleartext
        assertEquals(400, post(s, "pin=${s.pin}&manifest=file%3A%2F%2F%2Fetc%2Fpasswd"))
        assertEquals(400, post(s, "pin=${s.pin}&manifest=https%3A%2F%2Fexample.com%2Fm.json&key=bad%0Akey"))
        assertEquals(200, post(s, "pin=${s.pin}&manifest=http%3A%2F%2F192.168.1.5%2Fm.json"))
    }

    @Test fun crossOriginPostRejected() {
        val s = start()
        val port = s.url.substringAfterLast(':').trimEnd('/')
        val body = "pin=${s.pin}&manifest=https%3A%2F%2Fexample.com%2Fm.json"
        assertEquals(403, request(s,
            "POST /pair HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body"))
    }

    @Test fun sessionLogic() {
        val session = PairingSession("123456")
        assertEquals(403, session.submit("000000", true))
        assertEquals(400, session.submit("123456", false))
        assertEquals(200, session.submit("123456", true))
        assertEquals(410, session.submit("123456", true))
    }
}
