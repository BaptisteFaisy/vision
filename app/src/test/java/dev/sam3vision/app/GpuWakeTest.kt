package dev.sam3vision.app

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
            "réveil GPU (https://g.test) : 4.2 s, carte chaude",
            GpuWake.wakeLine("https://g.test", true, 4200),
        )
        assertEquals(
            "réveil GPU (https://g.test) : 0.0 s, échec",
            GpuWake.wakeLine("https://g.test", false, 12),
        )
    }
}
