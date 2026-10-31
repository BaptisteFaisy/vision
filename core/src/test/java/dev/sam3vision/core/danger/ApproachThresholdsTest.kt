package dev.sam3vision.core.danger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seuils d'expansion par label (PISTE2) : le mécanisme trie les pistes par
 * type (véhicules + personnes), et les appelants sans seuils gardent
 * exactement les verdicts personnes d'avant. JUnit seul.
 */
class ApproachThresholdsTest {

    private val vehicle = ApproachThresholds(minGrowthPerSec = 0.02f, centerMargin = 0.30f, maxTtcSec = 4f)
    private val person = ApproachThresholds()

    private fun estimator() = ApproachEstimator(thresholdsForLabel = { label ->
        if (label == "car") vehicle else person
    })

    /** Piste carrée de côté [side], centrée en [cx], sous le [label] donné. */
    private fun track(side: Float, id: Int = 1, label: String = "car", cx: Float = 0.5f): DangerTrack {
        val half = side / 2f
        return DangerTrack(id, label, 0.8f, listOf(cx - half, 0.5f - half, cx + half, 0.5f + half), 2, 0, 0L, 0L)
    }

    @Test
    fun `defaut - le label ne change rien, seuils personnes (non-regression)`() {
        val estimator = ApproachEstimator()
        estimator.update(track(0.2f, label = "car"), 0L)
        // +0,025 en 1 s : taux 0,025 < 0,03 personnes, même pour un véhicule.
        val reading = estimator.update(track(0.225f, label = "car"), 1000L)
        assertFalse(reading.approaching)
        assertNull(reading.ttcSec)
    }

    @Test
    fun `seuils par label - le vehicule alerte la ou la personne se tait`() {
        val estimator = estimator()
        estimator.update(track(0.07f, id = 1, label = "car"), 0L)
        estimator.update(track(0.07f, id = 2, label = "personne"), 0L)
        // Taux 0,025 : seuil véhicule (0,02) passé, seuil personne (0,03) non ; ttc 3,8 s sous le plafond.
        val car = estimator.update(track(0.095f, id = 1, label = "car"), 1000L)
        val walker = estimator.update(track(0.095f, id = 2, label = "personne"), 1000L)
        assertTrue(car.approaching)
        assertFalse(walker.approaching)
    }

    @Test
    fun `plafond ttc - l approche lointaine se tait pour le type plafonne`() {
        val estimator = estimator()
        estimator.update(track(0.2f, id = 1, label = "car"), 0L)
        // Taux 0,025 au-delà du seuil, mais ttc = 0,225 / 0,025 = 9 s > 4 s.
        val far = estimator.update(track(0.225f, id = 1, label = "car"), 1000L)
        assertFalse(far.approaching)
        assertNull(far.ttcSec)
    }

    @Test
    fun `plafond ttc - l approche franche passe sous le plafond`() {
        val estimator = estimator()
        estimator.update(track(0.2f, label = "car"), 0L)
        // ttc = 0,3 / 0,1 = 3 s ≤ 4 s.
        val near = estimator.update(track(0.3f, label = "car"), 1000L)
        assertTrue(near.approaching)
        assertNotNull(near.ttcSec)
        assertEquals(3f, near.ttcSec!!, 0.05f)
    }

    @Test
    fun `personnes - jamais de plafond ttc, meme lointain`() {
        val estimator = estimator()
        estimator.update(track(0.26f, label = "personne"), 0L)
        // Taux 0,04 au-delà du seuil, ttc = 0,3 / 0,04 = 7,5 s : la personne alerte quand même.
        val reading = estimator.update(track(0.3f, label = "personne"), 1000L)
        assertTrue(reading.approaching)
        assertEquals(7.5f, reading.ttcSec!!, 0.05f)
    }

    @Test
    fun `couloir par type - le vehicule excentre reste dedans`() {
        val estimator = estimator()
        estimator.update(track(0.2f, id = 1, label = "car", cx = 0.78f), 0L)
        estimator.update(track(0.2f, id = 2, label = "personne", cx = 0.78f), 0L)
        // |0,78 − 0,5| = 0,28 : dans le couloir véhicule (0,30), hors couloir personne (0,25).
        val car = estimator.update(track(0.3f, id = 1, label = "car", cx = 0.78f), 1000L)
        val walker = estimator.update(track(0.3f, id = 2, label = "personne", cx = 0.78f), 1000L)
        assertTrue(car.centered)
        assertTrue(car.approaching)
        assertFalse(walker.centered)
        assertFalse(walker.approaching)
    }
}
