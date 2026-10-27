package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.SegmentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pastilles de distance d'une photo : sténopé + hauteurs connues, sans écran ni réseau. */
class IseeDistancesTest {

    private val lens = LensSpec.fromHorizontalFov(60f)

    private fun obj(label: String, box: List<Float>) =
        SegObject(label = label, score = 0.9f, box = box, maskPngB64 = "")

    @Test
    fun `personne haute dans l'image est mesuree`() {
        // boîte de 240 px de haut sur 480 : 1,70 m × 554 px / 240 px ≈ 3,9 m
        val photo = SegmentResult(640, 480, listOf(obj("personne", listOf(280f, 100f, 360f, 340f))))
        val layer = layerOf(photo, lens)!!
        assertEquals(640, layer.width)
        assertEquals(480, layer.height)
        assertEquals(1, layer.readings.size)
        val meters = layer.readings.first().meters
        assertTrue("≈ 3,9 m attendu, obtenu $meters", meters in 3.5f..4.3f)
    }

    @Test
    fun `plusieurs objets donnent une pastille chacun`() {
        val photo = SegmentResult(
            640,
            480,
            listOf(
                obj("chaise", listOf(40f, 200f, 120f, 400f)),
                obj("table", listOf(300f, 260f, 520f, 420f)),
            ),
        )
        val layer = layerOf(photo, lens)!!
        assertEquals(listOf("chaise", "table"), layer.readings.map { it.label })
    }

    @Test
    fun `concept sans hauteur connue est ignore`() {
        val photo = SegmentResult(640, 480, listOf(obj("licorne", listOf(10f, 10f, 200f, 400f))))
        assertNull(layerOf(photo, lens))
    }

    @Test
    fun `photo vide ou de taille nulle ne produit rien`() {
        assertNull(layerOf(SegmentResult(640, 480, emptyList()), lens))
        assertNull(layerOf(SegmentResult(0, 0, listOf(obj("personne", listOf(1f, 1f, 9f, 40f)))), lens))
    }

    @Test
    fun `boite coupee par le bord bas n'est pas mesuree`() {
        // une couette mal segmentée en « personne », collée au bord bas : 31 m mesurés le 26/10
        val photo = SegmentResult(640, 480, listOf(obj("personne", listOf(0f, 300f, 640f, 480f))))
        assertNull(layerOf(photo, lens))
    }
}
