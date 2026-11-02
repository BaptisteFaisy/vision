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
 * le rouge isolé se tait, le rouge après un vert exige quand même deux
 * frames puis coupe (urgent), et les phrases exactes des deux langues sont
 * verrouillées. Binaire (ordre user du 31/10) : tout non-vert est rouge,
 * fini le doute.
 *
 * Seuils lus dans [CrossingAnnouncer] (1f vert / 2f rouge / 2,5 s /
 * rouge et vert répétés 2 s) : ce fichier les vérifie, il ne les change pas.
 */
class CrossingVoiceHysteresisTest {

    @Test
    fun `le rouge isole ne parle pas`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Red))
        assertNull(announcer.lastCue)
    }

    @Test
    fun `le rouge apres un vert exige deux frames puis coupe`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull("une seule frame rouge ne suffit pas", announcer.onFrame(800L, CrossingState.Red))
        val cue = announcer.onFrame(1_000L, CrossingState.Red)
        assertEquals("Don't cross yet", cue?.phrase)
        assertTrue(cue?.urgent ?: false)
    }

    @Test
    fun `le vert ne coupe jamais`() {
        val announcer = CrossingAnnouncer()
        announcer.onFrame(0L, CrossingState.Red)
        announcer.onFrame(200L, CrossingState.Red)
        repeat(4) { announcer.onFrame(3_000L + it * 200L, CrossingState.Green) }
        assertEquals("You can cross", announcer.lastCue?.phrase)
        assertFalse(announcer.lastCue?.urgent ?: true)
    }

    @Test
    fun `phrases francaises litterales de la voix`() {
        assertEquals("Rouge.", CrossingText.phrase(CrossingState.Red, SpeechLanguage.Fr))
        assertEquals("Vert.", CrossingText.phrase(CrossingState.Green, SpeechLanguage.Fr))
    }

    @Test
    fun `un vert-rouge-vert annonce des le premier vert`() {
        val announcer = CrossingAnnouncer()
        assertEquals(CrossingState.Green, announcer.onFrame(0L, CrossingState.Green)?.state)
        assertNull("un seul rouge ne coupe pas", announcer.onFrame(200L, CrossingState.Red))
        assertNull("répétition pas encore due", announcer.onFrame(400L, CrossingState.Green))
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
    }
}
