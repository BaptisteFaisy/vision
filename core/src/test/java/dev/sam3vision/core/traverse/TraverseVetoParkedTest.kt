package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto et preuve « garée » : une garée proche filmée à la main ne bloque plus
 * une fois prouvée, sans rien retirer à ce qui roule. Le tremblé est celui de
 * la vidéo Samsung 20261104_101016 : trop fort pour le test paire-de-frames
 * (dérive 2 %/s), qui bloquait donc à vie. Focale 1000 px, cadence 200 ms.
 */
class TraverseVetoParkedTest {

    private val focalPx = 1000f
    private val step = 200L

    private fun car(box: List<Float>, label: String = "car"): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    /** Garée à ~7 m, tremblée de ±6 px d'une frame à l'autre. */
    private fun shaky(index: Int): List<Float> {
        val x = 220f + if (index % 2 == 0) -6f else 6f
        return listOf(x, 140f, x + 200f, 340f)
    }

    private fun TraverseVeto.frame(index: Int, vararg detections: Detection, motion: FrameMotion? = null): TraverseDecision =
        update(false, detections.toList(), focalPx, index * step, motion = motion)

    @Test
    fun `garee proche tremblante - le veto tombe a la preuve`() {
        val veto = TraverseVeto()
        repeat(3) { index -> assertTrue("frame $index : pas encore prouvée", veto.frame(index, car(shaky(index))).veto) }
        repeat(10) { index ->
            val decision = veto.frame(3 + index, car(shaky(3 + index)))
            assertFalse("frame ${3 + index} : prouvée", decision.veto)
            assertEquals(null, decision.blockingLabel)
        }
    }

    @Test
    fun `garee proche - le guidage obstacles reste actif`() {
        val veto = TraverseVeto()
        var last = veto.frame(0, car(shaky(0)))
        repeat(6) { index -> last = veto.frame(index + 1, car(shaky(index + 1))) }
        assertFalse(last.veto)
        assertTrue("objet proche : guidage gardé", last.keepObstacleGuidance)
    }

    @Test
    fun `isParked - lit la derniere frame, reset oublie`() {
        val veto = TraverseVeto()
        repeat(6) { index -> veto.frame(index, car(shaky(index))) }
        val last = car(shaky(6))
        veto.frame(6, last)
        assertTrue(veto.isParked(last))
        veto.reset()
        assertFalse(veto.isParked(last))
    }

    @Test
    fun `voiture qui roule a cote d une garee - elle seule bloque`() {
        val veto = TraverseVeto()
        repeat(6) { index -> veto.frame(index, car(shaky(index))) }
        var last: TraverseDecision? = null
        repeat(4) { index ->
            val x = 40f + index * 60f
            val moving = car(listOf(x, 300f, x + 120f, 440f))
            last = veto.frame(6 + index, car(shaky(6 + index)), moving)
        }
        assertTrue("la garée n'exempte pas la voiture qui roule", last!!.veto)
        assertEquals("car", last!!.blockingLabel)
    }

    @Test
    fun `garee qui demarre - le veto revient`() {
        val veto = TraverseVeto()
        repeat(6) { index -> veto.frame(index, car(shaky(index))) }
        var vetoed = false
        repeat(8) { index ->
            val seconds = (index + 1) * step / 1000f
            val x = 220f + 640f * 0.11f * seconds * seconds
            vetoed = veto.frame(6 + index, car(listOf(x, 140f, x + 200f, 340f))).veto
        }
        assertTrue("démarrage : la preuve tombe, la proximité rebloque", vetoed)
    }

    @Test
    fun `pan rapide prouve - pas d exemption`() {
        // 0,33 image/s de fond : une boîte fixe dans l'image y suit la caméra.
        val pan = FrameMotion(MotionSample(6.4f, 0f, 1f, 0.9f), 96, 96, 200L)
        val veto = TraverseVeto()
        val fixed = listOf(220f, 140f, 420f, 340f)
        var last: TraverseDecision? = null
        repeat(10) { index -> last = veto.frame(index, car(fixed), motion = pan) }
        assertTrue("fixe sous pan rapide : veto", last!!.veto)
    }

    @Test
    fun `pan lent prouve - la garee reste exemptee`() {
        // 0,08 image/s de fond sur une frame : un hoquet de cadence ne casse pas la preuve.
        val slow = FrameMotion(MotionSample(1.5f, 0f, 1f, 0.9f), 96, 96, 200L)
        val veto = TraverseVeto()
        repeat(6) { index -> veto.frame(index, car(shaky(index))) }
        val last = car(shaky(6))
        veto.frame(6, last, motion = slow)
        assertTrue(veto.isParked(last))
    }

    /** Repères fixes (640×480) balayés à [shift] px par frame. */
    private fun poles(index: Int, shift: Float): Array<Detection> =
        List(5) { it }.map { Detection("pole", 0.8f, listOf(60f + it * 110f + shift * index, 30f, 80f + it * 110f + shift * index, 120f), 640, 480) }
            .toTypedArray()

    @Test
    fun `garee balayee - le veto tombe a la preuve et ne revient pas`() {
        val veto = TraverseVeto()
        repeat(4) { index -> veto.frame(index, car(listOf(220f - 16f * index, 140f, 420f - 16f * index, 340f)), *poles(index, -16f)) }
        repeat(8) { offset ->
            val index = 4 + offset
            val decision = veto.frame(index, car(listOf(220f - 16f * index, 140f, 420f - 16f * index, 340f)), *poles(index, -16f))
            assertFalse("frame $index", decision.veto)
        }
        assertTrue("les repères disent le balayage", veto.cameraMotion != null)
    }

    @Test
    fun `voiture qui roule pendant le balayage - le veto tient`() {
        val veto = TraverseVeto()
        repeat(12) { index ->
            val driving = car(listOf(100f + 30f * index, 140f, 300f + 30f * index, 340f))
            val decision = veto.frame(index, driving, *poles(index, -16f))
            assertTrue("frame $index", decision.veto)
        }
    }

    @Test
    fun `voiture qui entre plus vite que le balayage - sa croissance bloque`() {
        val veto = TraverseVeto()
        repeat(4) { index -> veto.frame(index, *poles(index, -8f)) }
        var blocked = false
        repeat(3) { offset ->
            val index = 4 + offset
            // Boîte lointaine, coupée à gauche : la voiture entre à 60 px par frame.
            val entering = Detection("car", 0.9f, listOf(0f, 200f, 20f + 60f * offset, 240f), 640, 480)
            blocked = blocked || veto.frame(index, entering, *poles(index, -8f)).veto
        }
        assertTrue(blocked)
    }
}
