package dev.sam3vision.app.live

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ips du direct : calculés sur l'intervalle **réel** entre deux frames publiées, 0
 * passé 20 000 ms (un trou ne garde pas l'ancien chiffre) — seule une frame tombée
 * dans la même milliseconde garde l'affichage (sinon 1000 ips inventés).
 */
class LiveFpsMeterTest {

    @Test
    fun `la premiere frame ne produit aucun intervalle`() {
        val meter = LiveFpsMeter()
        assertEquals(0L, meter.gapAt(1_000L))
        assertEquals(0f, LiveFpsMeter.fpsFor(0L, 0f), 0f)
    }

    @Test
    fun `intervalle reel converti en ips`() {
        val meter = LiveFpsMeter()
        meter.gapAt(1_000L)
        assertEquals(500L, meter.gapAt(1_500L))
        assertEquals(2f, LiveFpsMeter.fpsFor(500L, 0f), 0.0001f)
    }

    @Test
    fun `intervalle nul ou negatif rejete et valeur precedente gardee`() {
        assertEquals(3.5f, LiveFpsMeter.fpsFor(0L, 3.5f), 0f)
        assertEquals(3.5f, LiveFpsMeter.fpsFor(-12L, 3.5f), 0f)
    }

    @Test
    fun `trou de veille au dela de vingt secondes affiche zero`() {
        assertEquals(0f, LiveFpsMeter.fpsFor(20_001L, 1.25f), 0f)
        assertEquals(0.05f, LiveFpsMeter.fpsFor(20_000L, 1.25f), 0.0001f)
    }

    @Test
    fun `reset oublie la frame precedente`() {
        val meter = LiveFpsMeter()
        meter.gapAt(1_000L)
        meter.gapAt(1_500L)
        meter.reset()
        assertEquals(0L, meter.lastFrameAt)
        assertEquals(0L, meter.gapAt(9_000L))
    }

    @Test
    fun `une seule milliseconde d ecart vaut mille ips`() {
        val meter = LiveFpsMeter()
        meter.gapAt(1_000L)
        assertEquals(1L, meter.gapAt(1_001L))
        assertEquals(1_000f, LiveFpsMeter.fpsFor(1L, 0f), 0.0001f)
    }
}