package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

/**
 * Tangage tête depuis le quaternion DAT Motion : hochement pur, insensibilité
 * lacet/roulis, garde-fous. Convention pinnée : X droite / Y haut / Z porteur,
 * regard −Z, `+` = pique (à valider sur paire réelle, voir spec §8).
 */
class HeadPoseMathTest {

    @Test
    fun `identite donne tangage nul`() {
        assertEquals(0f, HeadPoseMath.pitchRadFromQuaternion(0f, 0f, 0f, 1f), 1e-6f)
    }

    @Test
    fun `hochement bas 30 degres donne tangage positif`() {
        // Rotation −30° autour de X : q = (−sin15°, 0, 0, cos15°).
        val pitch = HeadPoseMath.pitchRadFromQuaternion(-0.258819f, 0f, 0f, 0.9659258f)
        assertEquals(PI.toFloat() / 6f, pitch, 1e-3f)
    }

    @Test
    fun `regard haut 30 degres donne tangage negatif`() {
        val pitch = HeadPoseMath.pitchRadFromQuaternion(0.258819f, 0f, 0f, 0.9659258f)
        assertEquals(-PI.toFloat() / 6f, pitch, 1e-3f)
    }

    @Test
    fun `lacet 90 degres laisse le tangage nul`() {
        // Rotation 90° autour de Y : q = (0, sin45°, 0, cos45°).
        val pitch = HeadPoseMath.pitchRadFromQuaternion(0f, 0.7071068f, 0f, 0.7071068f)
        assertEquals(0f, pitch, 1e-3f)
    }

    @Test
    fun `roulis 45 degres laisse le tangage nul`() {
        // Rotation 45° autour de Z : q = (0, 0, sin22.5°, cos22.5°).
        val pitch = HeadPoseMath.pitchRadFromQuaternion(0f, 0f, 0.3826834f, 0.9238795f)
        assertEquals(0f, pitch, 1e-3f)
    }

    @Test
    fun `quaternion non normalise donne le meme tangage`() {
        val pitch = HeadPoseMath.pitchRadFromQuaternion(-0.517638f, 0f, 0f, 1.9318516f)
        assertEquals(PI.toFloat() / 6f, pitch, 1e-3f)
    }

    @Test
    fun `poseOrNull donne hauteur yeux et tangage`() {
        val pose = HeadPoseMath.poseOrNull(-0.258819f, 0f, 0f, 0.9659258f)
        assertNotNull(pose)
        assertEquals(CameraPose.GLASSES_HEIGHT_M, pose!!.heightMeters, 0f)
        assertEquals(PI.toFloat() / 6f, pose.pitchRad, 1e-3f)
    }

    @Test
    fun `poseOrNull rend null sur quaternion degenere`() {
        assertNull(HeadPoseMath.poseOrNull(0f, 0f, 0f, 0f))
        assertNull(HeadPoseMath.poseOrNull(Float.NaN, 0f, 0f, 1f))
    }
}
