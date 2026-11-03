package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.TraverseVeto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le fond traverse le patch : une garée qui dérive avec la caméra (pan)
 * bloque sans signal fond et se tait avec (piste tue). Miroir câblage du
 * test veto `pan sur garee` — ici c'est le passage du paramètre qui est
 * verrouillé. Séparé de `CrossingWiringTest` (plafond 500 lignes).
 */
class CrossingMotionWiringTest {

    @Test
    fun `fond cable au veto - pan sur garee tue`() {
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val bare = TraverseVeto()
        val aided = TraverseVeto()
        var bareVeto = false
        var aidedVeto = true
        repeat(13) { index ->
            val x = 220f + index * 5f
            val box = listOf(x, 140f, x + 200f, 340f)
            val atMs = index * 100L
            bareVeto = crossingPatchOf(
                CrossingAnnouncer(), bare, true,
                LiveFrame(640, 480, atMs, listOf(SegObject("car", 0.9f, box, ""))),
                LensSpec.Default, "", atMs, SpeechLanguage.Fr,
            )?.veto == true
            aidedVeto = crossingPatchOf(
                CrossingAnnouncer(), aided, true,
                LiveFrame(640, 480, atMs, listOf(SegObject("car", 0.9f, box, ""))),
                LensSpec.Default, "", atMs, SpeechLanguage.Fr, motion = motion,
            )?.veto == true
        }

        assertTrue("sans fond : dérive + proximité = veto", bareVeto)
        assertFalse("avec fond câblé : piste qui suit la caméra = silence", aidedVeto)
    }
}
