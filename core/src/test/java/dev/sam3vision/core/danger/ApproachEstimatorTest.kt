package dev.sam3vision.core.danger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maths de l'approche (JUnit seul) : grossissement centré = vient vers toi,
 * taux → temps-avant-contact, bords et rétrécissement = pas d'alerte.
 */
class ApproachEstimatorTest {

    /** Piste carrée centrée de côté [side] (normalisé 0..1). */
    private fun centered(side: Float, id: Int = 1): DangerTrack {
        val half = side / 2f
        return DangerTrack(id, "personne", 0.8f, listOf(0.5f - half, 0.5f - half, 0.5f + half, 0.5f + half), 2, 0, 0L, 0L)
    }

    @Test
    fun `une boite qui grossit vite et centree s'approche avec un ttc`() {
        val estimator = ApproachEstimator()
        estimator.update(centered(0.2f), 0L)
        // +0,1 de côté en 1 s : taux = 0,1/s ≥ 0,03, centré.
        val reading = estimator.update(centered(0.3f), 1000L)
        assertTrue(reading.approaching)
        assertTrue(reading.centered)
        assertNotNull(reading.ttcSec)
        // ttc = taille / taux = 0,3 / 0,1 = 3 s.
        assertEquals(3f, reading.ttcSec!!, 0.05f)
    }

    @Test
    fun `une boite qui retrecit ne s'approche pas`() {
        val estimator = ApproachEstimator()
        estimator.update(centered(0.3f), 0L)
        val reading = estimator.update(centered(0.2f), 1000L)
        assertFalse(reading.approaching)
        assertNull(reading.ttcSec)
    }

    @Test
    fun `une boite qui grossit sur le bord ne s'approche pas`() {
        val estimator = ApproachEstimator()
        val edge = { side: Float, cx: Float ->
            DangerTrack(1, "personne", 0.8f, listOf(cx - side / 2f, 0.4f, cx + side / 2f, 0.4f + side), 2, 0, 0L, 0L)
        }
        estimator.update(edge(0.2f, 0.9f), 0L)
        val reading = estimator.update(edge(0.3f, 0.9f), 1000L)
        assertFalse(reading.centered)
        assertFalse(reading.approaching)
    }

    @Test
    fun `une seule vue ne donne pas de taux`() {
        val estimator = ApproachEstimator()
        val reading = estimator.update(centered(0.3f), 0L)
        assertFalse(reading.approaching)
        assertNull(reading.ttcSec)
    }

    @Test
    fun `un grossissement lent sous le seuil ne s'approche pas`() {
        val estimator = ApproachEstimator()
        estimator.update(centered(0.2f), 0L)
        // +0,01 en 1 s : taux 0,01 < 0,03.
        val reading = estimator.update(centered(0.21f), 1000L)
        assertFalse(reading.approaching)
    }
}
