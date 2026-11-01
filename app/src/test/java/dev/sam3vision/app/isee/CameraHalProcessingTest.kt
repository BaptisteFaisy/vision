package dev.sam3vision.app.isee

import dev.sam3vision.app.isee.CameraHalProcessing.AvailableModes
import dev.sam3vision.app.isee.CameraHalProcessing.HalStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate de la coupe HAL : drapeau coupé → liaison inchangée (défaut), drapeau
 * allumé → OFF seulement là où le HAL l'annonce, sinon repli silencieux. La
 * lecture des modes ([CameraHalProcessing.readAvailableModes]) et l'application
 * touchent le téléphone et ne se prouvent pas en JVM : seule la décision,
 * pure, est testée ici (OFF = 0, doc AOSP).
 */
class CameraHalProcessingTest {

    private val toutOff = AvailableModes(
        edge = listOf(0, 1, 2),
        noise = listOf(0, 1),
        shading = listOf(0, 1),
        hotPixel = listOf(0, 1),
    )

    @Test
    fun `drapeau coupe capteur full off garde le defaut`() {
        assertTrue(CameraHalProcessing.selectStages(false, toutOff).isEmpty())
    }

    @Test
    fun `drapeau allume capteur full off coupe les quatre etages`() {
        assertEquals(
            setOf(HalStage.EDGE, HalStage.NOISE, HalStage.SHADING, HalStage.HOT_PIXEL),
            CameraHalProcessing.selectStages(true, toutOff),
        )
    }

    @Test
    fun `drapeau allume sans off annonce garde le defaut`() {
        val sansOff = AvailableModes(
            edge = listOf(1, 2),
            noise = listOf(1),
            shading = listOf(1),
            hotPixel = listOf(1),
        )

        assertTrue(CameraHalProcessing.selectStages(true, sansOff).isEmpty())
    }

    @Test
    fun `chaque etage est independant`() {
        val partiel = AvailableModes(
            edge = listOf(1),
            noise = listOf(0, 1),
            shading = null,
            hotPixel = emptyList(),
        )

        assertEquals(setOf(HalStage.NOISE), CameraHalProcessing.selectStages(true, partiel))
    }

    @Test
    fun `modes illisibles retombent sur le defaut`() {
        assertTrue(CameraHalProcessing.selectStages(true, null).isEmpty())
    }
}
