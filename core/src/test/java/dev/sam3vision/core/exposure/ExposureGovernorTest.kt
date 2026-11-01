package dev.sam3vision.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrouille le gouverneur de sous-exposition : il ne fait qu'assombrir
 * (jamais au-dessus de 0), cran par cran, avec un délai de stabilisation —
 * l'automatisme 3A du téléphone doit converger entre deux crans.
 */
class ExposureGovernorTest {

    /** Frame cramée : premier cran immédiat, puis un cran par délai de stabilisation. */
    @Test
    fun brightStepsDownOneNotchPerSettle() {
        val governor = ExposureGovernor(minIndex = -4)
        assertEquals(-1, governor.tick(mean = 210.0, nowMs = 0L, current = 0))
        assertNull(governor.tick(mean = 210.0, nowMs = 100L, current = -1))
        assertEquals(-2, governor.tick(mean = 210.0, nowMs = 700L, current = -1))
    }

    /** Plancher du téléphone : on ne descend jamais sous `minIndex`. */
    @Test
    fun brightClampsAtMinIndex() {
        val governor = ExposureGovernor(minIndex = -1)
        assertEquals(-1, governor.tick(mean = 255.0, nowMs = 0L, current = 0))
        assertNull(governor.tick(mean = 255.0, nowMs = 10_000L, current = -1))
    }

    /** Scène redevenue sombre : on remonte vers 0, cran par cran. */
    @Test
    fun darkStepsBackUpTowardZero() {
        val governor = ExposureGovernor(minIndex = -4)
        assertEquals(-1, governor.tick(mean = 210.0, nowMs = 0L, current = 0))
        assertEquals(0, governor.tick(mean = 30.0, nowMs = 700L, current = -1))
    }

    /** Jamais au-dessus de 0 : dans le noir, l'automatisme natif garde la main. */
    @Test
    fun darkAtZeroHolds() {
        val governor = ExposureGovernor(minIndex = -4)
        assertNull(governor.tick(mean = 0.0, nowMs = 0L, current = 0))
        assertNull(governor.tick(mean = 30.0, nowMs = 10_000L, current = 0))
    }

    /** Zone médiane : on ne touche à rien, même après un long délai. */
    @Test
    fun midRangeHolds() {
        val governor = ExposureGovernor(minIndex = -4)
        assertNull(governor.tick(mean = 120.0, nowMs = 0L, current = 0))
        assertNull(governor.tick(mean = 120.0, nowMs = 10_000L, current = -2))
    }

    /** bornes exactes : 190 et 50 tiennent (seuils stricts, pas d'oscillation). */
    @Test
    fun thresholdsAreStrict() {
        val governor = ExposureGovernor(minIndex = -4)
        assertNull(governor.tick(mean = 190.0, nowMs = 0L, current = 0))
        assertNull(governor.tick(mean = 50.0, nowMs = 10_000L, current = -1))
    }

    /** Téléphone sans compensation négative : inerte, aucun crash. */
    @Test
    fun noNegativeRangeIsInert() {
        val governor = ExposureGovernor(minIndex = 0)
        assertNull(governor.tick(mean = 255.0, nowMs = 0L, current = 0))
    }
}
