package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perte du passage en Traversée : « No crosswalk. » après 3 s, devant le latéral 101. */
class CrosswalkLostWiringTest {

    private fun obj(label: String, box: List<Float>, score: Float = 0.8f) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    private val crosswalk = listOf(obj("crosswalk", listOf(156f, 700f, 356f, 1100f)))

    @Test
    fun `perte au verdict, devant left et right`() {
        assertEquals(CrossingLevels.VERDICT, crossingLostLevel())
        assertTrue(crossingLostLevel() < crossingLateralLevel(true))
    }

    @Test
    fun `hors traversee la memoire retombe`() {
        CrosswalkLostWatch.reset()
        feedCrosswalkLost(false, emptyList(), 0L, SpeechLanguage.En)
        assertNull(CrosswalkLostWatch.flow.value)
    }

    @Test
    fun `silence tant que le passage est suivi`() {
        CrosswalkLostWatch.reset()
        feedCrosswalkLost(true, crosswalk, 0L, SpeechLanguage.En)
        feedCrosswalkLost(true, crosswalk, 10_000L, SpeechLanguage.En)
        assertNull(CrosswalkLostWatch.flow.value)
    }

    @Test
    fun `annonce apres 3 s sans passage, une seule fois`() {
        CrosswalkLostWatch.reset()
        feedCrosswalkLost(true, emptyList(), 1_000L, SpeechLanguage.En)
        assertNull(CrosswalkLostWatch.flow.value)
        feedCrosswalkLost(true, emptyList(), 4_000L, SpeechLanguage.En)
        assertEquals("No crosswalk.", CrosswalkLostWatch.flow.value)
        feedCrosswalkLost(true, emptyList(), 5_000L, SpeechLanguage.En)
        assertNull(CrosswalkLostWatch.flow.value)
    }

    @Test
    fun `retour du passage re-arme, rentree sans passage attend 3 s`() {
        CrosswalkLostWatch.reset()
        feedCrosswalkLost(true, emptyList(), 0L, SpeechLanguage.En)
        feedCrosswalkLost(true, emptyList(), 3_000L, SpeechLanguage.En)
        assertEquals("No crosswalk.", CrosswalkLostWatch.flow.value)
        feedCrosswalkLost(true, crosswalk, 4_000L, SpeechLanguage.En)
        assertNull(CrosswalkLostWatch.flow.value)
        feedCrosswalkLost(false, emptyList(), 5_000L, SpeechLanguage.En)
        feedCrosswalkLost(true, emptyList(), 60_000L, SpeechLanguage.En)
        assertNull(CrosswalkLostWatch.flow.value)
        feedCrosswalkLost(true, emptyList(), 63_000L, SpeechLanguage.En)
        assertEquals("No crosswalk.", CrosswalkLostWatch.flow.value)
    }
}
