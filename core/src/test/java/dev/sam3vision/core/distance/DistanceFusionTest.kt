package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusion sténopé/plan de sol dans [DistanceMeter] : cohérence ([DistanceFusion]), sol seul
 * quand la classe est inconnue, refus de l'objet coupé et bornes 0,3-60 m côté sol.
 *
 * Fixture du fichier voisin : image 512×384, calibrage par défaut 67° → f = 386,774 px, centre
 * optique (256, 192). Les nombres attendus sont calculés à la main avec les deux formules :
 * sténopé `H·f/h_px`, sol `h_cam / tan(atan((y_pieds − 192)/f) + pitch)`.
 */
class DistanceFusionTest {

    private companion object {
        const val WIDTH = 512
        const val HEIGHT = 384

        /** f = (512/2)/tan(33,5°) de [LensSpec.Default]. */
        const val FOCAL_PX = 386.774f
    }

    private fun detection(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, WIDTH, HEIGHT)

    /** Boîte de 200 px de haut, du haut 100 px — hauteur de classe exacte d'une personne à 3 m. */
    private fun tallPersonBox(bottom: Float = 300f): List<Float> = listOf(100f, 100f, 200f, bottom)

    /** Le meter avec la pose réglée aux Réglages. */
    private fun meter(pose: CameraPose?): DistanceMeter = DistanceMeter().apply { this.pose = pose }

    @Test
    fun `fusion sans valeur ou sans les deux branches`() {
        assertNull(DistanceFusion.combine(null, null))
        assertEquals(3.0f, DistanceFusion.combine(3.0f, null)!!, 0.001f)
        assertEquals(3.0f, DistanceFusion.combine(null, 3.0f)!!, 0.001f)
    }

    @Test
    fun `deux branches coherentes donnent leur moyenne`() {
        assertEquals(3.1f, DistanceFusion.combine(3.0f, 3.2f)!!, 0.001f)
        // borne haute de cohérence : 1,5 exactement est encore une moyenne
        assertEquals(3.75f, DistanceFusion.combine(3.0f, 4.5f)!!, 0.001f)
    }

    @Test
    fun `au-dela de la coherence, le stenope garde la main`() {
        assertEquals(3.0f, DistanceFusion.combine(3.0f, 4.8f)!!, 0.001f)
        assertEquals(3.0f, DistanceFusion.combine(3.0f, 1.9f)!!, 0.001f)
    }

    @Test
    fun `objet pose au sol sans hauteur de classe est mesure par le sol`() {
        val reading = meter(CameraPose(1.5f, 0.2f))
            .measure(WIDTH, HEIGHT, listOf(detection("licorne", listOf(100f, 100f, 200f, 296f))))
            .firstOrNull()!!
        // 1,5 / tan(atan(104/386,774) + 0,2) = 3,007 m
        assertEquals(3.007f, reading.meters, 0.02f)
    }

    @Test
    fun `branches coherentes fusionnees en moyenne pour une personne a 3 m`() {
        val reading = meter(CameraPose(1.5f, 0.15f))
            .measure(WIDTH, HEIGHT, listOf(detection("personne", tallPersonBox())))
            .firstOrNull()!!
        // sténopé 1,70·386,774/200 = 3,288 m ; sol 3,338 m ; moyenne 3,313 m
        assertEquals(3.313f, reading.meters, 0.02f)
        assertEquals(reading.rawMeters, reading.meters, 0.001f)
    }

    @Test
    fun `desaccord pose au sol : le stenope seul est rendu`() {
        val reading = meter(CameraPose(1.5f))
            .measure(WIDTH, HEIGHT, listOf(detection("personne", tallPersonBox())))
            .firstOrNull()!!
        // sol 5,372 m contre sténopé 3,288 m (rapport 1,63 > 1,5) : le sténopé garde la main
        assertEquals(3.288f, reading.meters, 0.02f)
    }

    @Test
    fun `sans pose reglee, rien de la branche sol`() {
        val detections = listOf(detection("personne", tallPersonBox()), detection("licorne", tallPersonBox()))
        assertEquals(1, meter(null).measure(WIDTH, HEIGHT, detections).size)
        assertEquals(3.288f, meter(CameraPose(10f)).measure(WIDTH, HEIGHT, detections).first().meters, 0.02f)
    }

    @Test
    fun `objet coupe refuse meme avec la pose`() {
        val posed = meter(CameraPose(1.5f, 0.2f))
        assertTrue(posed.measure(WIDTH, HEIGHT, listOf(detection("personne", listOf(100f, 200f, 200f, 384f)))).isEmpty())
        assertTrue(posed.measure(WIDTH, HEIGHT, listOf(detection("licorne", listOf(100f, 0f, 200f, 200f)))).isEmpty())
    }

    @Test
    fun `sol hors bornes ne rend aucune valeur`() {
        // 64 m sous l'horizon : au-delà de MAX_METERS, comme pour le sténopé
        val posed = meter(CameraPose(1.5f))
        assertTrue(posed.measure(WIDTH, HEIGHT, listOf(detection("licorne", listOf(100f, 150f, 200f, 201f)))).isEmpty())
        assertTrue(Pinhole.MAX_METERS < 64.4f)
        assertEquals(FOCAL_PX, LensSpec.Default.focalPx(WIDTH, HEIGHT), 0.01f)
    }
}