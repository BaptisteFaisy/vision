package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError
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
}
