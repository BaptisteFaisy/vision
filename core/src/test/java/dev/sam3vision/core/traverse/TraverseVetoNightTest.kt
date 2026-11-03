package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto de nuit sur garées : le pan excusé par le fond ne bloque pas (motion
 * câblée sonde), et la proximité à score faible exige confirmation (même
 * instance proche à la frame précédente) — un flicker SAM isolé ne bloque
 * pas. La croissance franche et la proximité à score franc bloquent, elles,
 * sans confirmation. Séparé de `TraverseVetoTest` (plafond 500 lignes).
 */
class TraverseVetoNightTest {

    private val focalPx = 1000f

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, width = 640, height = 480)

    private fun panMotion(): FrameMotion =
        FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)

    @Test
    fun `pan sur garee - veto sans fond, silence avec fond`() {
        // Garée qui dérive avec la caméra : sans signal fond, la dérive casse
        // l'arrêt prouvé et la proximité bloque ; avec fond, la piste qui suit
        // la caméra est tue (hush) et le veto se tait.
        val bare = TraverseVeto()
        val aided = TraverseVeto()
        val motion = panMotion()
        var bareLast: TraverseDecision? = null
        var aidedLast: TraverseDecision? = null
        repeat(13) { index ->
            val x = 220f + index * 5f
            val box = listOf(x, 140f, x + 200f, 340f)
            val atMs = index * 100L
            bareLast = bare.update(false, listOf(detection("car", 0.9f, box)), focalPx, atMs)
            aidedLast = aided.update(false, listOf(detection("car", 0.9f, box)), focalPx, atMs, motion = motion)
        }

        assertTrue("sans fond : dérive + proximité = veto", bareLast!!.veto)
        assertFalse("avec fond : piste qui suit la caméra = silence", aidedLast!!.veto)
        assertNull(aidedLast.blockingLabel)
    }

    @Test
    fun `proximite score faible - silence puis veto a la deuxieme frame`() {
        // 0.4 proche qui persiste (même instance, IoU ≥ 0.3) : silence à la
        // première frame, veto à la deuxième. La boîte dérive un peu pour ne
        // pas être prouvée immobile (l'arrêt prouvé prime toujours).
        val veto = TraverseVeto()
        val first = veto.update(false, listOf(detection("car", 0.4f, listOf(220f, 140f, 420f, 340f))), focalPx, 0L)
        val second = veto.update(false, listOf(detection("car", 0.4f, listOf(228f, 140f, 428f, 340f))), focalPx, 100L)

        assertFalse("apparition isolée à score faible : pas de veto", first.veto)
        assertTrue("même instance proche confirmée : veto", second.veto)
        assertEquals("car", second.blockingLabel)
    }

    @Test
    fun `proximite score franc - veto des la premiere frame`() {
        // Non-régression : 0.5 bloque aussitôt, sans confirmation.
        val veto = TraverseVeto()
        val decision = veto.update(false, listOf(detection("car", 0.5f, listOf(220f, 140f, 420f, 340f))), focalPx, 0L)

        assertTrue(decision.veto)
        assertEquals("car", decision.blockingLabel)
    }

    @Test
    fun `fragments qui alternent - jamais de veto`() {
        // Deux fragments proches qui alternent dans la même clé (IoU < 0.3
        // entre eux, toit/carrosserie de nuit) : jamais la même instance
        // proche deux fois de suite, donc jamais de veto de proximité.
        val veto = TraverseVeto()
        val boxes = listOf(
            listOf(200f, 140f, 400f, 340f),
            listOf(320f, 140f, 520f, 340f),
            listOf(200f, 140f, 400f, 340f),
            listOf(320f, 140f, 520f, 340f),
        )
        boxes.forEachIndexed { index, box ->
            val decision = veto.update(false, listOf(detection("car", 0.4f, box)), focalPx, index * 100L)
            assertFalse("frame $index : fragment alterné, pas de veto", decision.veto)
        }
    }
}
