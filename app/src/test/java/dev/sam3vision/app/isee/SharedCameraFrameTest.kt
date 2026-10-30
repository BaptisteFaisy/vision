package dev.sam3vision.app.isee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrouille le double tuyau de la frame partagée : le base64 pour la photo
 * lente (toutes les 2 s) et les **octets bruts** pour le direct (10+ im/s),
 * chacun avec son numéro anti-doublon.
 */
class SharedCameraFrameTest {

    @Test
    fun `la frame binaire est relue avec son numero, puis perime`() {
        SharedCameraFrame.clear()
        val now = System.currentTimeMillis()
        SharedCameraFrame.offerBytes(byteArrayOf(1, 2, 3))
        val frame = SharedCameraFrame.latestBytes(now, maxAgeMs = 300L)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame?.jpeg)
        // Périmée : le direct s'arrête au lieu de boucler une image figée.
        assertNull(SharedCameraFrame.latestBytes(now + 301L, maxAgeMs = 300L))
    }

    @Test
    fun `chaque offre binaire incremente le numero anti-doublon`() {
        SharedCameraFrame.clear()
        val now = System.currentTimeMillis()
        SharedCameraFrame.offerBytes(byteArrayOf(1))
        val first = SharedCameraFrame.latestBytes(now)?.seq
        SharedCameraFrame.offerBytes(byteArrayOf(2))
        val second = SharedCameraFrame.latestBytes(now)?.seq
        assertNotEquals(first, second)
    }

    @Test
    fun `le tuyau binaire ne perturbe pas la photo lente`() {
        SharedCameraFrame.clear()
        val now = System.currentTimeMillis()
        SharedCameraFrame.offer("base64-photo")
        SharedCameraFrame.offerBytes(byteArrayOf(9))
        assertEquals("base64-photo", SharedCameraFrame.photoOf(now)?.jpegBase64)
        assertArrayEquals(byteArrayOf(9), SharedCameraFrame.latestBytes(now)?.jpeg)
        SharedCameraFrame.clear()
        assertNull(SharedCameraFrame.photoOf(now))
        assertNull(SharedCameraFrame.latestBytes(now))
    }
}
