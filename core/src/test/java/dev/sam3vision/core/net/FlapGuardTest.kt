package dev.sam3vision.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Anti-flap paresseux ([FlapGuard]) : le premier échantillon sort aussitôt,
 * un flap A-B-A ne sort jamais, une valeur durable sort au premier échantillon
 * post-fenêtre, et le `fastPath` (retour en ligne) court-circuite l'attente.
 * Zéro thread, zéro minuteur : l'horloge est injectée par l'appelant.
 */
class FlapGuardTest {

    @Test
    fun `le premier echantillon est publie immediat`() {
        val guard = FlapGuard<String>()

        assertEquals("A", guard.sample("A", nowMs = 0L))
    }

    @Test
    fun `un flap A-B-A ne publie jamais B`() {
        val guard = FlapGuard<String>()
        guard.sample("A", nowMs = 0L)

        assertEquals("A", guard.sample("B", nowMs = 100L))
        assertEquals("A", guard.sample("A", nowMs = 200L))
        assertEquals("A", guard.sample("A", nowMs = 60_000L))
    }

    @Test
    fun `une valeur durable est publiee au premier sample post-fenetre`() {
        val guard = FlapGuard<String>()
        guard.sample("A", nowMs = 0L)

        assertEquals("A", guard.sample("B", nowMs = 100L))
        assertEquals("A", guard.sample("B", nowMs = 100L + STABLE_WINDOW_MS - 1))
        assertEquals("B", guard.sample("B", nowMs = 100L + STABLE_WINDOW_MS))
        assertEquals("B", guard.sample("B", nowMs = 100L + STABLE_WINDOW_MS + 5_000L))
    }

    @Test
    fun `le fastPath publie immediat sans attendre la fenetre`() {
        val guard = FlapGuard<Boolean>(fastPath = { candidate, stable -> candidate && !stable })
        guard.sample(false, nowMs = 0L)

        assertEquals(true, guard.sample(true, nowMs = 1L))
    }

    @Test
    fun `un offline transitoire est absorbe un durable est publie`() {
        val guard = FlapGuard<Boolean>(fastPath = { candidate, stable -> candidate && !stable })
        guard.sample(true, nowMs = 0L)

        assertEquals(true, guard.sample(false, nowMs = 100L))
        assertEquals(true, guard.sample(true, nowMs = 200L))
        assertEquals(true, guard.sample(false, nowMs = 1_000L))
        assertEquals(false, guard.sample(false, nowMs = 1_000L + STABLE_WINDOW_MS))
    }
}
