package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mouvement caméra lu sur les objets fixes : un balayage se mesure, une caméra
 * posée reste muette, et rien qui roule ne peut passer pour un balayage. Frames
 * 1000×1000 à la cadence du direct (200 ms).
 */
class AnchorMotionTest {

    private val step = 200L

    private fun det(label: String, x1: Float, y1: Float, x2: Float, y2: Float): Detection =
        Detection(label, 0.8f, listOf(x1, y1, x2, y2), width = 1000, height = 1000)

    private fun poles(shift: Float, count: Int = 4): List<Detection> =
        List(count) { det("pole", 200f + it * 150f + shift, 300f, 240f + it * 150f + shift, 700f) }

    private fun AnchorMotion.run(frames: Int, of: (Int) -> List<Detection>): CameraStep? {
        var last: CameraStep? = null
        repeat(frames) { last = update(of(it), it * step) }
        return last
    }

    @Test
    fun `balayage - le pas de la camera se lit sur les poteaux`() {
        val anchors = AnchorMotion()
        val last = anchors.run(6) { poles(-40f * it) }
        assertEquals(-0.04f, last!!.dx, 0.001f)
        assertEquals(0f, last.dy, 0.001f)
        assertNotNull("0,2 image/s : balayage franc", anchors.motion)
    }

    @Test
    fun `camera posee - aucun balayage, meme tremblee`() {
        val anchors = AnchorMotion()
        anchors.run(8) { poles(if (it % 2 == 0) -2f else 2f) }
        assertNull(anchors.motion)
    }

    @Test
    fun `poteau coupe en haut et en bas - il dit encore sa place en largeur`() {
        val anchors = AnchorMotion()
        val last = anchors.run(5) { index ->
            List(4) { det("pole", 200f + it * 150f - 40f * index, 0f, 240f + it * 150f - 40f * index, 1000f) }
        }
        assertEquals(-0.04f, last!!.dx, 0.001f)
    }

    @Test
    fun `moins de trois reperes - on ne conclut pas`() {
        assertNull(AnchorMotion().run(6) { poles(-40f * it, count = 2) })
    }

    @Test
    fun `une file de voitures qui glisse n'est pas un balayage`() {
        val anchors = AnchorMotion()
        val last = anchors.run(6) { index ->
            List(5) { det("car", 100f + it * 170f - 40f * index, 400f, 240f + it * 170f - 40f * index, 500f) }
        }
        assertNull(last)
        assertNull(anchors.motion)
    }

    @Test
    fun `panneaux poses sur des vehicules - jamais des reperes`() {
        val last = AnchorMotion().run(6) { index ->
            List(4) {
                val x = 100f + it * 220f - 40f * index
                listOf(det("van", x, 300f, x + 200f, 600f), det("sign", x + 50f, 400f, x + 100f, 450f))
            }.flatten()
        }
        assertNull(last)
    }

    @Test
    fun `un repere qui bouge seul ne deplace pas la camera`() {
        val last = AnchorMotion().run(6) { index ->
            poles(0f, count = 5) + det("sign", 100f + 40f * index, 100f, 140f + 40f * index, 140f)
        }
        assertEquals(0f, last!!.dx, 0.001f)
    }

    @Test
    fun `reperes en desaccord - doute`() {
        // Deux frames : ensuite les deux groupes sortent du rayon de recherche, ils ne votent plus.
        val last = AnchorMotion().run(2) { index ->
            poles(-40f * index, count = 3) + List(3) {
                det("sign", 300f + it * 150f + 40f * index, 100f, 340f + it * 150f + 40f * index, 140f)
            }
        }
        assertNull(last)
    }

    @Test
    fun `sans date - rien`() {
        assertNull(AnchorMotion().update(poles(0f), -1L))
    }

    @Test
    fun `reset - tout repart de zero`() {
        val anchors = AnchorMotion()
        anchors.run(6) { poles(-40f * it) }
        anchors.reset()
        assertNull(anchors.motion)
        assertNull("aucun repère gardé", anchors.update(poles(0f), 0L))
    }
}
