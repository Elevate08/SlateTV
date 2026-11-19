package com.stasisstate.slatetv.wallpaper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedResourceRunnerTest {

    private val executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private class FakeResource(val payload: String = "ok") : Closeable {
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }

    /** Ignores interrupts so it models a Binder call that only returns when the provider replies. */
    private fun CountDownLatch.awaitUninterruptibly() {
        while (true) {
            try {
                await()
                return
            } catch (_: InterruptedException) {
                // keep waiting, like a wedged Binder transaction
            }
        }
    }

    @Test
    fun `timeout fires onCancel and closes a late-returning resource`() = runBlocking {
        val runner = BoundedResourceRunner(executor, maxPerKey = 2)
        val releaseOpen = CountDownLatch(1)
        val late = FakeResource()
        val cancelCalled = AtomicBoolean(false)
        val consumed = AtomicBoolean(false)

        val result = runner.run(
            key = "stalled.provider",
            timeoutMs = 100,
            onCancel = { cancelCalled.set(true) },
            open = {
                releaseOpen.awaitUninterruptibly()
                late
            },
            consume = { consumed.set(true); it.payload },
        )

        assertNull(result)
        assertTrue(cancelCalled.get())
        assertEquals(0, late.closeCount.get())

        // Descriptor arrives only after the caller has already timed out and cleaned up.
        releaseOpen.countDown()
        assertTrue("late descriptor must be closed", late.closed.await(2, TimeUnit.SECONDS))
        assertFalse("consume must not run after cancellation", consumed.get())
        assertEquals(1, late.closeCount.get())
    }

    @Test
    fun `successful open is consumed and closed exactly once`() = runBlocking {
        val runner = BoundedResourceRunner(executor)
        val resource = FakeResource("bytes")
        val cancelCalled = AtomicBoolean(false)

        val result = runner.run("ok.provider", 2_000, { cancelCalled.set(true) }, { resource }) { it.payload }

        assertEquals("bytes", result)
        assertFalse(cancelCalled.get())
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `stalled authority hits its permit cap without blocking another authority`() = runBlocking {
        val runner = BoundedResourceRunner(executor, maxPerKey = 2)
        val releaseStalled = CountDownLatch(1)
        val stalledEntered = CountDownLatch(2)
        val stalledOpens = AtomicInteger(0)
        val stalledResources = List(2) { FakeResource() }

        val stalledOpen: () -> FakeResource = {
            val index = stalledOpens.getAndIncrement()
            stalledEntered.countDown()
            releaseStalled.awaitUninterruptibly()
            stalledResources[index]
        }

        val inFlight = List(2) {
            async(Dispatchers.Default) {
                runner.run("stalled.provider", 10_000, {}, stalledOpen) { it.payload }
            }
        }
        assertTrue(withContext(Dispatchers.IO) { stalledEntered.await(2, TimeUnit.SECONDS) })

        // Third request for the wedged authority fails fast and never reaches the provider.
        val start = System.nanoTime()
        val rejected = runner.run("stalled.provider", 10_000, {}, stalledOpen) { it.payload }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertNull(rejected)
        assertTrue("cap rejection should be immediate, took ${elapsedMs}ms", elapsedMs < 500)
        assertEquals(2, stalledOpens.get())

        // A different authority is unaffected.
        val other = FakeResource("other")
        assertEquals("other", runner.run("healthy.provider", 2_000, {}, { other }) { it.payload })
        assertEquals(1, other.closeCount.get())

        // Once the wedged workers finish, their permits are returned.
        releaseStalled.countDown()
        inFlight.forEach { assertEquals("ok", it.await()) }
        val recovered = FakeResource("recovered")
        assertEquals("recovered", runner.run("stalled.provider", 2_000, {}, { recovered }) { it.payload })
    }

    @Test
    fun `permit is held by a timed-out worker until it actually finishes`() = runBlocking {
        val runner = BoundedResourceRunner(executor, maxPerKey = 1)
        val release = CountDownLatch(1)
        val late = FakeResource()

        assertNull(runner.run("slow", 50, {}, { release.awaitUninterruptibly(); late }) { it.payload })
        // Caller timed out, but the worker is still wedged: the authority stays capped.
        assertNull(runner.run("slow", 2_000, {}, { FakeResource("x") }) { it.payload })

        release.countDown()
        assertTrue(late.closed.await(2, TimeUnit.SECONDS))
        // Permit release happens in the worker's finally; poll briefly for it.
        var value: String? = null
        repeat(50) {
            if (value == null) {
                value = runner.run("slow", 2_000, {}, { FakeResource("back") }) { it.payload }
                if (value == null) Thread.sleep(20)
            }
        }
        assertEquals("back", value)
    }
}
