package dev.sam3vision.app

import dev.sam3vision.core.local.LocalReason
import dev.sam3vision.core.mask.MaskMotion
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calque local côté app : la logique **pure** est testée en JVM (drapeau de build, ligne
 * d'état, choix de la surface affichée, transform de rendu). Le rendu lui-même
 * (`Canvas`, `Bitmap`) n'est pas testable ici : une JVM nue ne sait pas fabriquer d'image.
 */
class LiveLocalOverlayTest {

    @Test
    fun `un drapeau de build vide laisse le reglage decider`() {
        assertTrue(localOverlayFlag("", true))
        assertEquals(false, localOverlayFlag("", false))
    }

    @Test
    fun `un drapeau de build vrai prime sur un reglage faux`() {
        assertTrue(localOverlayFlag("true", false))
        assertTrue(localOverlayFlag("ON", false))
    }

    @Test
    fun `un drapeau de build faux prime sur un reglage vrai`() {
        assertEquals(false, localOverlayFlag("false", true))
        assertEquals(false, localOverlayFlag("0", true))
    }

    @Test
    fun `un drapeau inconnu ne decide de rien`() {
        assertTrue(localOverlayFlag("peut-etre", true))
        assertEquals(false, localOverlayFlag("peut-etre", false))
    }

    @Test
    fun `sans image locale la surface garde les masques du serveur`() {
        val serveur = listOf("masque-a", "masque-b")

        assertEquals(serveur, overlayLayers(serveur, null))
    }

    @Test
    fun `avec une image locale la surface ne montre que le calque`() {
        val serveur = listOf("masque-a", "masque-b")

        val couches = overlayLayers(serveur, "calque")

        assertEquals(1, couches.size)
        assertEquals("calque", couches.first())
    }

    @Test
    fun `un calque eteint ne dit rien dans la ligne d etat`() {
        assertEquals("", localStatusPart(enabled = false, active = false, fps = 0f))
    }

    @Test
    fun `un calque actif annonce sa cadence`() {
        val part = localStatusPart(enabled = true, active = true, fps = 27.8f)

        assertTrue(part.contains("27.8 fps"))
    }

    @Test
    fun `un calque inerte donne son motif`() {
        val part = localStatusPart(enabled = true, active = false, fps = 0f, note = "no estimator")

        assertTrue(part.contains("idle"))
        assertTrue(part.contains("no estimator"))
    }

    @Test
    fun `le motif du refus est lisible`() {
        assertEquals("no estimator", localReasonNote(LocalReason.NO_ESTIMATOR))
        assertEquals("stale keyframe", localReasonNote(LocalReason.STALE_KEYFRAME))
        assertEquals("", localReasonNote(LocalReason.PAINT))
    }

    @Test
    fun `le transform remet la propagation a l echelle du plan masque`() {
        val warp = localWarpFor(MaskMotion(4.0, 2.0), maskWidth = 512, maskHeight = 206)

        assertEquals("256 px de référence valent 512 px de masque", 8f, warp.dx, 0.001f)
        assertEquals(4f, warp.dy, 0.001f)
        assertEquals("le pivot est le centre du plan", 256f, warp.pivotX, 0.001f)
        assertEquals(103f, warp.pivotY, 0.001f)
    }

    @Test
    fun `l echelle du mouvement traverse le transform`() {
        val warp = localWarpFor(MaskMotion(0.0, 0.0, 1.5, 0.9), maskWidth = 512, maskHeight = 206)

        assertEquals(1.5f, warp.scale, 0.001f)
    }

    @Test
    fun `la confiance du keyframe est le meilleur score de la frame`() {
        val frame = LiveFrame(
            width = 512,
            height = 206,
            elapsedMs = 10L,
            objects = listOf(
                SegObject("personne", 0.42f, listOf(0f, 0f, 1f, 1f), ""),
                SegObject("chien", 0.77f, listOf(0f, 0f, 1f, 1f), ""),
            ),
        )

        assertEquals(0.77f, keyframeConfidence(frame), 0.001f)
        assertEquals(0f, keyframeConfidence(LiveFrame(1, 1, 1L, emptyList())), 0.001f)
    }
}