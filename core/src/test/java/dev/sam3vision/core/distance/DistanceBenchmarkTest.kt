package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banc de précision : caméra virtuelle exacte (focale et centre connus), objets
 * à distances connues projetés en pixels puis inversés par [Pinhole] et
 * [GroundPlane]. Écart résiduel = erreur de la formule, pas du terrain.
 */
class DistanceBenchmarkTest {

    private companion object {
        const val PERSON_HEIGHT = 1.70f
        const val VIRTUAL_FOCAL = 800f
        const val VIRTUAL_WIDTH = 640
        const val VIRTUAL_HEIGHT = 480
        const val VIRTUAL_CY = 239.5f
        const val CAM_HEIGHT = 1.5f
    }

    /**
     * Boîte de [heightPx] pour la formule pure : elle peut dépasser du cadre (2720 px
     * à 0,5 m), `Pinhole` ne vérifie pas les bords — c'est `isVerticallyCut`, en amont
     * dans `DistanceMeter`, qui écarte les boîtes coupées.
     */
    private fun box(heightPx: Float): List<Float> = listOf(100f, 100f, 200f, 100f + heightPx)

    @Test
    fun `stenope inverse la projection au centimetre de 0 5 a 50 m`() {
        for (distance in listOf(0.5f, 1f, 2f, 3f, 5f, 10f, 20f, 50f)) {
            val heightPx = PERSON_HEIGHT * VIRTUAL_FOCAL / distance
            val back = Pinhole.distanceMeters(box(heightPx), PERSON_HEIGHT, VIRTUAL_FOCAL)!!
            assertEquals("aller-retour à $distance m", distance, back, 0.005f)
        }
    }

    @Test
    fun `plan de sol inverse la projection au centimetre`() {
        for (pitch in listOf(0f, 0.15f, 0.3f)) {
            val pose = CameraPose(CAM_HEIGHT, pitch)
            var covered = 0
            for (distance in listOf(2f, 3f, 5f, 10f, 20f)) {
                val row = GroundPlane.rowAt(distance, pose, VIRTUAL_FOCAL, VIRTUAL_CY)
                    ?: continue
                if (row < 0f || row > VIRTUAL_HEIGHT) continue
                val back = GroundPlane.forwardMeters(row, pose, VIRTUAL_FOCAL, VIRTUAL_CY, VIRTUAL_HEIGHT)!!
                assertEquals("aller-retour à $distance m (pitch $pitch)", distance, back, 0.01f)
                covered++
            }
            assertTrue("pitch $pitch : trop peu de distances cadrées ($covered)", covered >= 2)
        }
    }

    @Test
    fun `plan de sol exact avec un centre optique decentre`() {
        val pose = CameraPose(CAM_HEIGHT, 0.15f)
        for (distance in listOf(5f, 10f, 20f)) {
            val row = GroundPlane.rowAt(distance, pose, VIRTUAL_FOCAL, 250f)!!
            val back = GroundPlane.forwardMeters(row, pose, VIRTUAL_FOCAL, 250f, VIRTUAL_HEIGHT)!!
            assertEquals(distance, back, 0.01f)
        }
    }

    @Test
    fun `enveloppe de quantification, un pixel sur la hauteur de boite`() {
        // focale du direct (67° sur 512 px) : l'erreur relative d'1 px double avec la distance
        val focal = LensSpec.Default.focalPx(512, 384)
        val envelope = listOf(2f to 0.005f, 5f to 0.01f, 10f to 0.02f, 20f to 0.04f)
        for ((distance, bound) in envelope) {
            val heightPx = PERSON_HEIGHT * focal / distance
            val near = Pinhole.distanceMeters(box(heightPx + 1f), PERSON_HEIGHT, focal)!!
            val far = Pinhole.distanceMeters(box(heightPx - 1f), PERSON_HEIGHT, focal)!!
            assertTrue("±1 px à $distance m sous $bound", Math.abs(near - distance) / distance < bound)
            assertTrue("±1 px à $distance m sous $bound", Math.abs(far - distance) / distance < bound)
        }
    }

    @Test
    fun `branches d'accord, la fusion rend la valeur commune`() {
        assertEquals(3.3f, DistanceFusion.combine(3.3f, 3.3f)!!, 0.001f)
        assertEquals(3.3f, DistanceFusion.combine(3.3f, 3.3f, 0.5f, 2f)!!, 0.001f)
    }
}
