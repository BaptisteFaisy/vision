package dev.sam3vision.app

import dev.sam3vision.core.net.ApiError
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un seul réveil en vol par endpoint, jamais sans endpoint, durée loggée. */
class GpuWakeTest {

    @Test
    fun `un endpoint vide ne reveille rien`() = runBlocking {
        var calls = 0
        val woken = GpuWake.wake("  ", health = { calls += 1; Result.success(true) }, log = {})

        assertFalse(woken)
        assertEquals(0, calls)
    }

    @Test
    fun `deux reveils meme endpoint n'envoient qu'un health`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Result<Boolean>>()
        var calls = 0
        val health: suspend (String) -> Result<Boolean> = {
            calls += 1
            entered.complete(Unit)
            gate.await()
        }
        val first = async { GpuWake.wake("https://gpu.test", health, log = {}) }
        entered.await()
        val second = GpuWake.wake("https://gpu.test", health, log = {})

        assertFalse(second)
        gate.complete(Result.success(true))
        assertTrue(first.await())
        assertEquals(1, calls)
    }

    @Test
    fun `deux endpoints differents reveillent en parallele`() = runBlocking {
        var calls = 0
        val health: suspend (String) -> Result<Boolean> = { calls += 1; Result.success(true) }
        val a = async { GpuWake.wake("https://a.test", health, log = {}) }
        val b = async { GpuWake.wake("https://b.test", health, log = {}) }

        assertTrue(a.await())
        assertTrue(b.await())
        assertEquals(2, calls)
    }

    @Test
    fun `la ligne de log donne la duree et l'issue`() {
        assertEquals(
            "GPU wake (https://g.test): 4.2 s, warm card",
            GpuWake.wakeLine("https://g.test", true, 4200),
        )
        assertEquals(
            "GPU wake (https://g.test): 0.0 s, failure negative response",
            GpuWake.wakeLine("https://g.test", false, 12),
        )
    }

    @Test
    fun `l'echec porte le code http`() {
        assertEquals(
            "GPU wake (https://g.test): 1.5 s, failure 404",
            GpuWake.wakeLine("https://g.test", false, 1500, ApiError.BackendStopped()),
        )
        assertEquals(
            "GPU wake (https://g.test): 1.5 s, failure 500",
            GpuWake.wakeLine("https://g.test", false, 1500, ApiError.Http(500, "boom")),
        )
    }

    @Test
    fun `l'echec par timeout dit delai depasse`() {
        assertEquals("timed out", GpuWake.wakeMotif(ApiError.Timeout()))
        assertEquals("timed out", GpuWake.wakeMotif(SocketTimeoutException("read timed out")))
        assertEquals(
            "GPU wake (https://g.test): 180.0 s, failure timed out",
            GpuWake.wakeLine("https://g.test", false, 180_000, ApiError.Timeout()),
        )
    }

    @Test
    fun `l'echec inconnu porte la premiere ligne du message`() {
        assertEquals("boom", GpuWake.wakeMotif(RuntimeException("boom")))
        assertEquals("panne", GpuWake.wakeMotif(RuntimeException("panne\nsuite")))
        assertEquals("x".repeat(80), GpuWake.wakeMotif(RuntimeException("x".repeat(200))))
        assertEquals("RuntimeException", GpuWake.wakeMotif(RuntimeException()))
    }

    @Test
    fun `wake loggue le motif de l'echec`() = runBlocking {
        val lines = mutableListOf<String>()
        val woken = GpuWake.wake(
            "https://g.test",
            health = { Result.failure(ApiError.Timeout()) },
            log = { lines += it },
        )

        assertTrue(woken)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].endsWith("failure timed out"))
    }
}
