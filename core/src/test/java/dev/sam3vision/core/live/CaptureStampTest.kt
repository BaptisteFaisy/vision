package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** Horloge de capture : le timestamp du HAL n'est utilisé que s'il est comparable. */
class CaptureStampTest {

    @Test
    fun `un timestamp capteur coherent est garde tel quel`() {
        val now = 10_000_000_000L
        assertEquals(now - 12_000_000L, captureStamp(now - 12_000_000L, now))
    }

    @Test
    fun `un timestamp decale par la veille est remplace par l'instant present`() {
        // 27,45 s d'écart : exactement ce que le Redmi a livré le 2026-10-26
        val now = 100_000_000_000L
        assertEquals(now, captureStamp(now - 27_450_000_000L, now))
    }

    @Test
    fun `un timestamp absent ne bloque pas la chronologie`() {
        assertEquals(42L, captureStamp(0L, 42L))
        assertEquals(42L, captureStamp(-1L, 42L))
    }
}
