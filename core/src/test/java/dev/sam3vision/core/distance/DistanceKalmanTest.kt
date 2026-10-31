package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtre de Kalman position+vitesse par piste. */
class DistanceKalmanTest {

    private val t0 = 1_000_000L

    @Test
    fun `premier appel rend la mesure brute vitesse nulle`() {
        val estimate = DistanceKalman().filter(trackId = 7, rawMeters = 4.2f, atMs = t0)
        assertEquals(4.2f, estimate.meters, 0f)
        assertEquals(0f, estimate.velocityMps, 0f)
    }

    @Test
    fun `distance constante converge et incertitude diminue`() {
        val kalman = DistanceKalman()
        var estimate = kalman.filter(1, 5f, atMs = t0)
        val firstVariance = estimate.variance
        repeat(9) { step ->
            estimate = kalman.filter(1, 5f, atMs = t0 + (step + 1) * 1_000L)
        }
        assertEquals(5f, estimate.meters, 0.05f)
        assertEquals(0f, estimate.velocityMps, 0.1f)
        assertTrue(estimate.variance < firstVariance)
    }

    @Test
    fun `approche a vitesse constante suivie sans retard`() {
        val kalman = DistanceKalman()
        var estimate = kalman.filter(1, 10f, atMs = t0)
        repeat(9) { step ->
            val truth = 10f - (step + 1)
            estimate = kalman.filter(1, truth, atMs = t0 + (step + 1) * 1_000L)
        }
        assertEquals(1f, estimate.meters, 0.3f)
        assertEquals(-1f, estimate.velocityMps, 0.3f)
    }

    @Test
    fun `outlier isole attenue sans decrocher la piste`() {
        val kalman = DistanceKalman()
        repeat(8) { step -> kalman.filter(1, 5f, atMs = t0 + step * 1_000L) }
        val outlier = kalman.filter(1, 6f, atMs = t0 + 8_000L)
        assertTrue(outlier.meters < 5.5f)
        var recovery = outlier
        repeat(3) { step ->
            recovery = kalman.filter(1, 5f, atMs = t0 + (9 + step) * 1_000L)
        }
        assertEquals(5f, recovery.meters, 0.2f)
    }

    @Test
    fun `saut franc reinitialise la piste en douceur`() {
        val kalman = DistanceKalman()
        repeat(5) { step -> kalman.filter(1, 5f, atMs = t0 + step * 1_000L) }
        val jump = kalman.filter(1, 10f, atMs = t0 + 5_000L)
        assertEquals(10f, jump.meters, 0f)
        assertEquals(0f, jump.velocityMps, 0f)
        assertEquals(1, kalman.size())
    }

    @Test
    fun `piste muette au-dela du timeout oubliee`() {
        val kalman = DistanceKalman(timeoutMs = 2_000L)
        kalman.filter(1, 5f, atMs = t0)
        val fresh = kalman.filter(1, 9f, atMs = t0 + 2_001L)
        assertEquals(9f, fresh.meters, 0f)
        assertEquals(0f, fresh.velocityMps, 0f)
        assertEquals(1, kalman.size())
    }

    @Test
    fun `confiance basse suit plus lentement que confiance haute`() {
        val confident = DistanceKalman()
        val unsure = DistanceKalman()
        confident.filter(1, 5f, atMs = t0)
        unsure.filter(1, 5f, atMs = t0)
        var high = KalmanEstimate(5f, 0f, 1f)
        var low = KalmanEstimate(5f, 0f, 1f)
        repeat(3) { step ->
            val atMs = t0 + (step + 1) * 1_000L
            high = confident.filter(1, 4.5f, confidence = 1f, atMs = atMs)
            low = unsure.filter(1, 4.5f, confidence = 0.05f, atMs = atMs)
        }
        assertTrue(high.meters < low.meters)
        assertEquals(4.5f, high.meters, 0.2f)
        assertTrue(low.meters > 4.6f)
    }

    @Test
    fun `prediction extrapole la vitesse sans modifier la piste`() {
        val kalman = DistanceKalman()
        kalman.filter(1, 10f, atMs = t0)
        repeat(5) { step -> kalman.filter(1, 10f - (step + 1), atMs = t0 + (step + 1) * 1_000L) }
        val predicted = kalman.predict(1, t0 + 5_500L)!!
        // ~5 m à t0+5 s, vitesse ≈ −1 m/s : 0,5 s plus tard ≈ 4,5 m
        assertEquals(4.5f, predicted.meters, 0.4f)
        assertEquals(1, predicted.trackId)
        // prédire n'est pas mesurer : la piste n'a pas bougé
        val again = kalman.predict(1, t0 + 5_500L)!!
        assertEquals(predicted.meters, again.meters, 0f)
        assertEquals(1, kalman.size())
    }

    @Test
    fun `prediction garde label boite et degrade la confiance`() {
        val kalman = DistanceKalman(timeoutMs = 2_000L)
        val box = listOf(10f, 20f, 30f, 120f)
        kalman.filter(1, 5f, confidence = 0.8f, atMs = t0, label = "personne", box = box)
        val predicted = kalman.predict(1, t0 + 1_000L)!!
        assertEquals("personne", predicted.label)
        assertEquals(box, predicted.box)
        assertEquals(5f, predicted.meters, 0.001f)
        assertEquals(0.4f, predicted.confidence, 0.001f)
    }

    @Test
    fun `prediction inconnue perimee ou passee rend null`() {
        val kalman = DistanceKalman(timeoutMs = 2_000L)
        kalman.filter(1, 5f, atMs = t0)
        assertEquals(null, kalman.predict(99, t0 + 500L))
        assertEquals(null, kalman.predict(1, t0 + 2_001L))
        assertEquals(null, kalman.predict(1, t0 - 1L))
        assertEquals(1, kalman.predictions(t0 + 500L).size)
        assertTrue(kalman.predictions(t0 + 2_001L).isEmpty())
    }
}
