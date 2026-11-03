package dev.sam3vision.core.keyframe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Les deux politiques du scheduler : la référence L13 (500 ms, 3/s) et la
 * politique terrain (300 ms, 4/s — mesure v18, serveur ~52 ms file vide).
 * Mêmes garde-fous des deux côtés : un seul vol, rafale 2, repli, 12 s.
 */
class KeyframeConfigTest {

    @Test
    fun `la reference L13 reste a 500 ms et 3 par s`() {
        val ref = KeyframeConfig()

        assertEquals(500L, ref.maxAgeMs)
        assertEquals(500L, ref.effectiveMaxAgeMs)
        assertEquals(3f, ref.budgetRatePerSec, 0f)
        assertEquals(6, ref.maxRequestsPerSecond)
    }

    @Test
    fun `la politique terrain vise 300 ms et 4 par s`() {
        val field = KeyframeConfig.Field

        assertEquals(300L, field.maxAgeMs)
        assertEquals(300L, field.effectiveMaxAgeMs)
        assertEquals(4f, field.budgetRatePerSec, 0f)
        assertEquals(2, field.budgetBurst)
        assertEquals(200L, field.minIntervalMs)
        assertEquals(12_000L, field.inFlightTimeoutMs)
        assertEquals(7, field.maxRequestsPerSecond)
    }
}
