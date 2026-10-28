package dev.sam3vision.core.depth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-régression du SIGABRT Scudo du 2026-10-28 : les runs natifs ne se
 * chevauchent jamais et `close()` ne libère jamais pendant un run en vol.
 */
class SerialRunGuardTest {

    @Test
    fun `runs concurrents serialises sans chevauchement`() {
        val guard = SerialRunGuard()
        val inside = AtomicInteger(0)
        val peak = AtomicInteger(0)
        runBlocking(Dispatchers.Default) {
            val jobs = (0 until 16).map {
                async {
                    guard.run {
                        val now = inside.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        Thread.sleep(5)
                        inside.decrementAndGet()
                        now
                    }
                }
            }
            val outcomes = jobs.awaitAll()
            assertTrue(outcomes.all { it.isSuccess })
        }
        assertEquals(1, peak.get())
    }

    @Test
    fun `close pendant un run laisse finir puis libere une fois`() {
        val guard = SerialRunGuard()
        val entered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val releases = AtomicInteger(0)
        val outcome = runBlocking(Dispatchers.Default) {
            val job = async {
                guard.run {
                    entered.countDown()
                    val ok = releaseGate.await(5, TimeUnit.SECONDS)
                    if (!ok) {
                        throw IllegalStateException("sonde bloquée")
                    }
                    "fini"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            guard.close { releases.incrementAndGet() }
            assertEquals(0, releases.get())
            releaseGate.countDown()
            job.await()
        }
        assertTrue(outcome.isSuccess)
        assertEquals("fini", outcome.getOrNull())
        assertEquals(1, releases.get())
    }

    @Test
    fun `run apres close est refuse`() {
        val guard = SerialRunGuard()
        val releases = AtomicInteger(0)
        guard.close { releases.incrementAndGet() }
        val outcome = runBlocking { guard.run { "jamais" } }
        assertTrue(outcome.isFailure)
        assertEquals(1, releases.get())
    }

    @Test
    fun `double close libere une seule fois`() {
        val guard = SerialRunGuard()
        val releases = AtomicInteger(0)
        guard.close { releases.incrementAndGet() }
        guard.close { releases.incrementAndGet() }
        assertEquals(1, releases.get())
    }

    @Test
    fun `run en attente pendant close est refuse sans toucher la session`() {
        val guard = SerialRunGuard()
        val entered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val releases = AtomicInteger(0)
        runBlocking(Dispatchers.Default) {
            val first = async {
                guard.run {
                    entered.countDown()
                    val ok = releaseGate.await(5, TimeUnit.SECONDS)
                    if (!ok) {
                        throw IllegalStateException("sonde bloquée")
                    }
                    "premier"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async { guard.run { "second" } }
            Thread.sleep(50)
            guard.close { releases.incrementAndGet() }
            releaseGate.countDown()
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isFailure)
        }
        assertEquals(1, releases.get())
    }
}
