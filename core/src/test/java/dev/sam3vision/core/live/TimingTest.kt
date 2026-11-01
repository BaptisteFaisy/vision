package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat temps du guidage rapproché (audit foule du 01/11) : portée en
 * secondes, temps de contact, projection de l'âge de la mesure.
 */
class TimingTest {

    @Test
    fun `la portee suit la vitesse de marche`() {
        assertEquals(4.06f, Timing.rangeFor(1.4f), 0.02f)
        assertEquals(5.8f, Timing.rangeFor(2.0f), 0.02f)
    }

    @Test
    fun `la portee est bornee`() {
        assertEquals(Timing.MIN_RANGE_M, Timing.rangeFor(0.3f), 0.001f)
        assertEquals(Timing.MAX_RANGE_M, Timing.rangeFor(9f), 0.001f)
    }

    @Test
    fun `une vitesse absente garde la portee historique`() {
        assertEquals(4.06f, Timing.rangeFor(null), 0.02f)
        assertEquals(4.06f, Timing.rangeFor(Float.NaN), 0.02f)
    }

    @Test
    fun `le temps de contact est distance sur fermeture`() {
        assertEquals(2f, Timing.ttcSeconds(4f, 2f)!!, 0.001f)
        assertEquals(1.43f, Timing.ttcSeconds(4f, 2.8f)!!, 0.02f)
    }

    @Test
    fun `un objet immobile ou qui s eloigne n a pas de temps de contact`() {
        assertNull(Timing.ttcSeconds(3f, 0f))
        assertNull(Timing.ttcSeconds(3f, -0.4f))
        assertNull(Timing.ttcSeconds(3f, null))
        assertNull(Timing.ttcSeconds(null, 2f))
        assertNull(Timing.ttcSeconds(0f, 2f))
    }

    @Test
    fun `un bruit de fermeture ne compte pas comme une approche`() {
        assertNull(Timing.ttcSeconds(3f, Timing.MIN_CLOSING_MPS / 2f))
    }

    @Test
    fun `la projection rattrape l age de la mesure`() {
        assertEquals(2.6f, Timing.projectDistance(4f, 2f, 700L)!!, 0.001f)
    }

    @Test
    fun `sans fermeture connue la distance est rendue telle quelle`() {
        assertEquals(4f, Timing.projectDistance(4f, null, 700L)!!, 0.001f)
    }

    @Test
    fun `une projection ne fait jamais reculer ni passer derriere`() {
        assertEquals(4f, Timing.projectDistance(4f, -2f, 700L)!!, 0.001f)
        assertEquals(0f, Timing.projectDistance(1f, 5f, 1_000L)!!, 0.001f)
    }

    @Test
    fun `un age nul ne change rien`() {
        assertEquals(3f, Timing.projectDistance(3f, 2f, 0L)!!, 0.001f)
    }

    @Test
    fun `l age est jamais negatif`() {
        assertEquals(0L, Timing.ageOf(1_000L, 900L))
        assertEquals(250L, Timing.ageOf(1_000L, 1_250L))
    }

    @Test
    fun `une mesure trop vieille est perimee`() {
        assertTrue(Timing.isStale(Timing.STALE_MS + 1))
        assertFalse(Timing.isStale(Timing.STALE_MS))
        assertFalse(Timing.isStale(0))
    }

    @Test
    fun `l urgence est un temps de contact court`() {
        assertTrue(Timing.isUrgent(1.0f))
        assertTrue(Timing.isUrgent(Timing.URGENT_TTC_S))
        assertFalse(Timing.isUrgent(1.5f))
        assertFalse(Timing.isUrgent(null))
    }
}
