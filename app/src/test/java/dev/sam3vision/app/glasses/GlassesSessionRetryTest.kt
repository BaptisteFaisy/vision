package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rejou d'ouverture de session ([SessionRetry]) : un seul, et seulement le
 * transitoire — le reste se dit au lieu de tourner en rond.
 */
class GlassesSessionRetryTest {

    @Test
    fun `l erreur inattendue se rejoue une fois`() {
        val first = SessionRetry()
        assertTrue(first.shouldRetry(DeviceSessionError.UNEXPECTED_ERROR))
        assertFalse(first.next().shouldRetry(DeviceSessionError.UNEXPECTED_ERROR))
    }

    @Test
    fun `la session encore la se rejoue une fois`() {
        val first = SessionRetry()
        assertTrue(first.shouldRetry(DeviceSessionError.SESSION_ALREADY_EXISTS))
        assertFalse(first.next().shouldRetry(DeviceSessionError.SESSION_ALREADY_EXISTS))
    }

    @Test
    fun `les autres refus ne se rejouent pas`() {
        val retry = SessionRetry()
        assertFalse(retry.shouldRetry(DeviceSessionError.NO_ELIGIBLE_DEVICE))
        assertFalse(retry.shouldRetry(DeviceSessionError.DEVICE_DISCONNECTED))
        assertFalse(retry.shouldRetry(DeviceSessionError.INSUFFICIENT_SDK_VERSION))
    }

    @Test
    fun `une session morte avant deux secondes est une mort rapide`() {
        // Refus mesuré le 31/10/2026 : session tuée en ~80 ms, jamais STARTED.
        assertTrue(isQuickDeath(1_000L, 1_080L))
        assertTrue(isQuickDeath(1_000L, 2_999L))
        assertFalse(isQuickDeath(1_000L, 3_000L))
        assertFalse(isQuickDeath(1_000L, 61_000L))
    }

    @Test
    fun `les nouveaux essais apres mort rapide s espacent 1s 3s 5s`() {
        assertEquals(1_000L, quickDeathDelayMs(1))
        assertEquals(3_000L, quickDeathDelayMs(2))
        assertEquals(5_000L, quickDeathDelayMs(3))
        assertEquals(5_000L, quickDeathDelayMs(99))
    }

    @Test
    fun `trois essais automatiques avant de conclure au refus`() {
        assertEquals(3, QUICK_DEATH_MAX_RETRIES)
        assertEquals(2_000L, QUICK_DEATH_MS)
    }
}
