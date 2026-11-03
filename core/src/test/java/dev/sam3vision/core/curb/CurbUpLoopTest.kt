package dev.sam3vision.core.curb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boucle « curb up » : active après 2 frames d'une montée à moins d'1 m,
 * tenue par hystérésis (sortie à 1,2 m, 3 trous), jamais sur une
 * descente, un sens incertain ou à 1 m et au-delà.
 */
class CurbUpLoopTest {

    /** Montée à [meters] (bord `curb` : sens montant). */
    private fun montee(meters: Float) =
        CurbSignal(detected = true, meters = meters, centerX = 0.5f, edge = CurbEdge.Up)

    /** Signal au sens [edge] à [meters]. */
    private fun sens(edge: CurbEdge?, meters: Float = 0.8f) =
        CurbSignal(detected = true, meters = meters, centerX = 0.5f, edge = edge)

    @Test
    fun `2 frames montee sous 1m = boucle active`() {
        val boucle = CurbUpLoop()

        assertFalse(boucle.onSignal(montee(0.8f)))
        assertTrue(boucle.onSignal(montee(0.8f)))
    }

    @Test
    fun `1 seule frame = inactive`() {
        val boucle = CurbUpLoop()

        assertFalse(boucle.onSignal(montee(0.5f)))
    }

    @Test
    fun `descente et incertain = jamais actifs`() {
        val boucle = CurbUpLoop()

        repeat(3) { assertFalse(boucle.onSignal(sens(CurbEdge.Down))) }
        repeat(3) { assertFalse(boucle.onSignal(sens(CurbEdge.Uncertain))) }
    }

    @Test
    fun `1m pile et au-dela = inactifs`() {
        val boucle = CurbUpLoop()

        repeat(2) { assertFalse("1 m pile", boucle.onSignal(montee(1.0f))) }
        repeat(2) { assertFalse("au-delà", boucle.onSignal(montee(1.5f))) }
    }

    @Test
    fun `hysteresis tient a 1,1m et sort a 1,2m`() {
        val boucle = CurbUpLoop()
        repeat(2) { boucle.onSignal(montee(0.8f)) }

        assertTrue("tient à 1,1 m", boucle.onSignal(montee(1.1f)))
        assertTrue("1er manqué", boucle.onSignal(montee(1.2f)))
        assertTrue("2e manqué", boucle.onSignal(montee(1.2f)))
        assertFalse("3e manqué = sortie", boucle.onSignal(montee(1.2f)))
    }

    @Test
    fun `3 trous = sortie`() {
        val boucle = CurbUpLoop()
        repeat(2) { boucle.onSignal(montee(0.8f)) }

        assertTrue(boucle.onSignal(CurbSignal.none()))
        assertTrue(boucle.onSignal(CurbSignal.none()))
        assertFalse(boucle.onSignal(CurbSignal.none()))
    }

    @Test
    fun `signal sans distance = non qualifiant`() {
        val boucle = CurbUpLoop()
        val sansDistance = CurbSignal(detected = true, meters = null, edge = CurbEdge.Up)

        repeat(3) { assertFalse(boucle.onSignal(sansDistance)) }
    }

    @Test
    fun `edge null = montee`() {
        val boucle = CurbUpLoop()

        assertFalse(boucle.onSignal(sens(null)))
        assertTrue(boucle.onSignal(sens(null)))
    }

    @Test
    fun `reset = inactive, reconfirme sur 2 frames`() {
        val boucle = CurbUpLoop()
        repeat(2) { boucle.onSignal(montee(0.8f)) }

        boucle.reset()

        assertFalse(boucle.onSignal(montee(0.8f)))
        assertTrue(boucle.onSignal(montee(0.8f)))
    }
}
