package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusion sténopé/plan de sol dans [DistanceMeter] : cohérence ([DistanceFusion]), sol seul
 * quand la classe est inconnue, repli bas-au-contact signalé (haut coupé refusé),
 * bornes 0,3-60 m côté sol.
 *
 * Fixture du fichier voisin : image 512×384, calibrage par défaut 67° → f = 386,774 px, centre
 * optique (255,5, 191,5). Les nombres attendus sont calculés à la main avec les deux formules :
 * sténopé `H·f/h_px`, sol `h_cam / tan(atan((y_pieds − 191,5)/f) + pitch)`.
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
        // 1,5 / tan(atan(104,5/386,774) + 0,2) = 2,998 m
        assertEquals(2.998f, reading.meters, 0.01f)
    }

    @Test
    fun `branches coherentes fusionnees en moyenne ponderee pour une personne a 3 m`() {
        val reading = meter(CameraPose(1.5f, 0.15f))
            .measure(WIDTH, HEIGHT, listOf(detection("personne", tallPersonBox())))
            .firstOrNull()!!
        // sténopé 1,70·386,774/200 = 3,288 m ; sol 3,328 m ; pondérée (0,943/1) 3,308 m
        assertEquals(3.308f, reading.meters, 0.01f)
        assertEquals(reading.rawMeters, reading.meters, 0.001f)
    }

    @Test
    fun `desaccord sur la pose, le stenope seul est rendu`() {
        val reading = meter(CameraPose(1.5f))
            .measure(WIDTH, HEIGHT, listOf(detection("personne", tallPersonBox())))
            .firstOrNull()!!
        // sol 5,347 m contre sténopé 3,288 m (rapport 1,63 > 1,5) : le sténopé garde la main
        assertEquals(3.288f, reading.meters, 0.01f)
    }

    @Test
    fun `sans pose reglee, rien de la branche sol`() {
        val detections = listOf(detection("personne", tallPersonBox()), detection("licorne", tallPersonBox()))
        assertEquals(1, meter(null).measure(WIDTH, HEIGHT, detections).size)
        assertEquals(3.288f, meter(CameraPose(10f)).measure(WIDTH, HEIGHT, detections).first().meters, 0.02f)
    }

    @Test
    fun `coupe bas avec pose donne un repli signale, coupe haut refuse`() {
        val posed = meter(CameraPose(1.5f, 0.2f))
        val reading = posed.measure(WIDTH, HEIGHT, listOf(detection("personne", listOf(100f, 200f, 200f, 384f)))).single()
        assertTrue(reading.cutFallback)
        // sol au bord bas : 1,5 / tan(atan((384 - 191,5)/386,774) + 0,2) = 1,925 m
        assertEquals(1.925f, reading.meters, 0.02f)
        assertEquals(reading.meters, reading.rawMeters, 0f)
        assertTrue(reading.confidence in 0f..DistanceMeter.CUT_FALLBACK_MAX_CONFIDENCE)
        // tête coupée : toujours refusée, même avec la pose
        assertTrue(posed.measure(WIDTH, HEIGHT, listOf(detection("licorne", listOf(100f, 0f, 200f, 200f)))).isEmpty())
        // collée aux deux bords : refusée aussi (pas de sténopé à 31 m déguisé)
        assertTrue(posed.measure(WIDTH, HEIGHT, listOf(detection("personne", listOf(0f, 0f, 512f, 384f)))).isEmpty())
    }

    @Test
    fun `sol hors bornes ne rend aucune valeur`() {
        // 64 m sous l'horizon : au-delà de MAX_METERS, comme pour le sténopé
        val posed = meter(CameraPose(1.5f))
        assertTrue(posed.measure(WIDTH, HEIGHT, listOf(detection("licorne", listOf(100f, 150f, 200f, 201f)))).isEmpty())
        assertTrue(Pinhole.MAX_METERS < 64.4f)
        assertEquals(FOCAL_PX, LensSpec.Default.focalPx(WIDTH, HEIGHT), 0.01f)
    }

    @Test
    fun `poids inegaux tirent la moyenne vers la branche sure`() {
        assertEquals(3.05f, DistanceFusion.combine(3.0f, 3.2f, 3f, 1f)!!, 0.001f)
        assertEquals(3.15f, DistanceFusion.combine(3.0f, 3.2f, 1f, 3f)!!, 0.001f)
        // poids invalides : repli sur la moyenne simple
        assertEquals(3.1f, DistanceFusion.combine(3.0f, 3.2f, 0f, -2f)!!, 0.001f)
    }

    @Test
    fun `divergence, le stenope garde la main quel que soit son poids`() {
        assertEquals(3.0f, DistanceFusion.combine(3.0f, 4.8f, 2f, 1f)!!, 0.001f)
        assertEquals(3.0f, DistanceFusion.combine(3.0f, 4.8f, 1f, 2f)!!, 0.001f)
        assertEquals(3.0f, DistanceFusion.combine(3.0f, 4.8f)!!, 0.001f)
    }

    @Test
    fun `poids stenope suit la tolerance de la classe`() {
        val fused = meter(CameraPose(1.5f, 0.15f))
            .measure(WIDTH, HEIGHT, listOf(detection("personne", tallPersonBox())))
            .firstOrNull()!!
        // personne : poids 1/1,06 = 0,943 contre 1 au sol — entre les deux branches
        val standing = 1.70f * FOCAL_PX / 200f
        val ground = GroundPlane.forwardMeters(300f, CameraPose(1.5f, 0.15f), FOCAL_PX, 191.5f, HEIGHT)!!
        val expected = (standing * (1f / 1.06f) + ground) / (1f / 1.06f + 1f)
        assertEquals(expected, fused.meters, 0.01f)
    }

    @Test
    fun `de loin, le stenope pese plus que le sol dans la fusion`() {
        // pieds à 10 m (dépression 0,149 rad → poids sol 0,74), boîte un peu courte (stenope 10,5 m)
        val box = listOf(100f, 186.9f, 200f, 249.52f)
        val fused = meter(CameraPose(1.5f))
            .measure(WIDTH, HEIGHT, listOf(detection("personne", box)))
            .firstOrNull()!!
        val standing = Pinhole.distanceMeters(box, 1.70f, FOCAL_PX)!!
        val ground = GroundPlane.forwardMeters(249.52f, CameraPose(1.5f), FOCAL_PX, 191.5f, HEIGHT)!!
        val depression = GroundPlane.depressionRad(249.52f, CameraPose(1.5f), FOCAL_PX, 191.5f)
        val groundWeight = GroundWeight.of(depression)
        val standingWeight = KnownSizes.fusionWeight("personne")
        assertTrue("dépression $depression sous la référence", depression < GroundWeight.REFERENCE_RAD)
        assertTrue("sol $groundWeight sous sténopé $standingWeight", groundWeight < standingWeight)
        val expected = (standing * standingWeight + ground * groundWeight) / (standingWeight + groundWeight)
        assertEquals(expected, fused.meters, 0.005f)
    }

    @Test
    fun `centre optique decentre deplace la branche sol, pas le stenope`() {
        val lens = LensSpec(67f, 52.85f, principalYFrac = 200f / 383f)
        val offCenter = DistanceMeter(lens).apply { pose = CameraPose(1.5f, 0.2f) }
        val centered = meter(CameraPose(1.5f, 0.2f))
        val box = listOf(100f, 100f, 200f, 296f)
        val moved = offCenter.measure(WIDTH, HEIGHT, listOf(detection("licorne", box))).first()
        val reference = centered.measure(WIDTH, HEIGHT, listOf(detection("licorne", box))).first()
        val expected = GroundPlane.forwardMeters(296f, CameraPose(1.5f, 0.2f), lens.focalPx(WIDTH, HEIGHT), 200f, HEIGHT)!!
        assertEquals(expected, moved.meters, 0.01f)
        assertTrue("le centre optique change la mesure sol", moved.meters != reference.meters)
    }
}