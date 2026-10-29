package dev.sam3vision.core.danger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kalman XYAH (JUnit seul) : initiation, poursuite d'un mobile, pas de NaN. */
class KalmanXyahTest {

    private fun finite(state: KalmanXyah.State): Boolean =
        state.mean.all { it.isFinite() } && state.cov.all { row -> row.all { it.isFinite() } }

    @Test
    fun `l'initiation pose la mesure avec des vitesses nulles`() {
        val state = KalmanXyah.initiate(doubleArrayOf(0.5, 0.5, 0.5, 0.4))
        assertEquals(0.5, state.mean[0], 1e-12)
        assertEquals(0.0, state.mean[4], 1e-12)
        assertTrue(finite(state))
    }

    @Test
    fun `la prediction avance a vitesse constante`() {
        var state = KalmanXyah.initiate(doubleArrayOf(0.5, 0.5, 0.5, 0.4))
        state = KalmanXyah.update(state, doubleArrayOf(0.52, 0.5, 0.5, 0.4))
        state = KalmanXyah.update(KalmanXyah.predict(state), doubleArrayOf(0.54, 0.5, 0.5, 0.4))
        val predicted = KalmanXyah.predict(state)
        // Vitesse apprise ≈ +0,02/frame : la prédiction continue vers la droite.
        assertTrue(predicted.mean[0] > 0.54)
        assertTrue(finite(predicted))
    }

    @Test
    fun `la correction tire l'etat vers la mesure`() {
        val state = KalmanXyah.initiate(doubleArrayOf(0.5, 0.5, 0.5, 0.4))
        val predicted = KalmanXyah.predict(state)
        val corrected = KalmanXyah.update(predicted, doubleArrayOf(0.6, 0.5, 0.5, 0.4))
        assertTrue(corrected.mean[0] > predicted.mean[0])
        assertTrue(corrected.mean[0] < 0.6)
        assertTrue(finite(corrected))
    }
}
