package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hystérésis voix du mode Traversée : compléments à [CrossingAnnouncerTest].
 *
 * Verrouille ce que les tests existants ne disent pas littéralement :
 * le doute isolé se tait, le rouge après un vert exige quand même deux
 * frames, le doute après un vert se tait dans le délai puis parle sans
 * couper hors délai, et les phrases exactes des deux langues sont verrouillées.
 *
 * Seuils lus dans [CrossingAnnouncer] (4f vert / 2f rouge / 2,5 s /
 * rouge répété 1 s) : ce fichier les vérifie, il ne les change pas.
 */
class CrossingVoiceHysteresisTest {

    @Test
    fun `le doute isole ne parle pas`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Unknown))
        assertNull(announcer.lastCue)
    }

    @Test
    fun `le doute exige deux frames et ne coupe pas`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Unknown))
        val cue = announcer.onFrame(200L, CrossingState.Unknown)
        assertEquals("Unsure, wait.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
        assertEquals(CrossingState.Unknown, cue?.state)
    }

    @Test
    fun `le rouge apres un vert exige deux frames puis coupe`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull("une seule frame rouge ne suffit pas", announcer.onFrame(800L, CrossingState.Red))
        val cue = announcer.onFrame(1_000L, CrossingState.Red)
        assertEquals("Red, wait.", cue?.phrase)
        assertTrue(cue?.urgent ?: false)
    }

    @Test
    fun `le doute apres un vert dans le delai se tait`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull(announcer.onFrame(800L, CrossingState.Unknown))
        assertNull(announcer.onFrame(1_000L, CrossingState.Unknown))
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
    }

    @Test
    fun `le doute apres un vert hors delai parle sans couper`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull(announcer.onFrame(4_000L, CrossingState.Unknown))
        val cue = announcer.onFrame(4_200L, CrossingState.Unknown)
        assertEquals("Unsure, wait.", cue?.phrase)
        assertFalse("seul le retour au rouge est urgent", cue?.urgent ?: true)
    }

    @Test
    fun `le vert ne coupe jamais`() {
        val announcer = CrossingAnnouncer()
        announcer.onFrame(0L, CrossingState.Red)
        announcer.onFrame(200L, CrossingState.Red)
        repeat(4) { announcer.onFrame(3_000L + it * 200L, CrossingState.Green) }
        assertEquals("Green, cross now.", announcer.lastCue?.phrase)
        assertFalse(announcer.lastCue?.urgent ?: true)
    }

    @Test
    fun `phrases francaises litterales de la voix`() {
        assertEquals("Rouge, attendez.", CrossingText.phrase(CrossingState.Red, SpeechLanguage.Fr))
        assertEquals("Vert, traversez.", CrossingText.phrase(CrossingState.Green, SpeechLanguage.Fr))
        assertEquals("Doute, attendez.", CrossingText.phrase(CrossingState.Unknown, SpeechLanguage.Fr))
    }

    @Test
    fun `un scintillement vert-rouge-vert ne parle pas`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Green))
        assertNull(announcer.onFrame(200L, CrossingState.Red))
        assertNull(announcer.onFrame(400L, CrossingState.Green))
        assertNull(announcer.lastCue)
    }
}
