package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.live.AntiRepeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés (niveau, texte, clip). */
private class NoCutFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String, val clipId: String? = null)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text, clipId)
    }

    override fun cut() = Unit
}

/**
 * Rien ne coupe (ordre user du 03/11/2026) : le mot en cours va au bout,
 * puis se dit le plus prioritaire en attente — à égalité le plus frais,
 * c'est-à-dire la position flèche du moment. Doublon exact du mot en
 * cours : jeté (tour rendu). Attente périmée : rendue, jamais dite.
 * Coupe : attente abandonnée.
 */
class NoCutPendingTest {

    private val speaker = NoCutFakeSpeaker()

    @Before
    fun brancher() {
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
    }

    @Test
    fun `mot de guidage re-poste, le plus frais parle a la fin`() {
        VoiceBus.post(OutdoorLevel.FALLBACK, "Gauche.")
        VoiceBus.post(OutdoorLevel.FALLBACK, "Droite.")

        assertEquals(1, speaker.said.size)
        VoiceBus.onSpeechIdle()
        assertEquals(
            listOf(
                NoCutFakeSpeaker.Said(OutdoorLevel.FALLBACK, "Gauche."),
                NoCutFakeSpeaker.Said(OutdoorLevel.FALLBACK, "Droite."),
            ),
            speaker.said,
        )
    }

    @Test
    fun `doublon exact du mot en cours jete, tour rendu`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, 0L))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE)

        assertFalse(VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE))
        assertEquals(1, speaker.said.size)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, 0L))
    }

    @Test
    fun `attente perimee derriere le chat, rendue et jamais dite`() {
        val now = longArrayOf(10_000L)
        VoiceBus.clock = { now[0] }
        VoiceBus.chatSpeaking(true)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_SOFT, 10_000L))
        assertTrue(VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.", antiRepeatKey = AntiRepeat.KEY_HUMAN_SOFT))
        assertEquals(0, speaker.said.size)

        now[0] = 15_000L
        VoiceBus.chatSpeaking(false)

        // Périmée (5 s > 4 s) : jamais dite, tour rendu, bus libre.
        assertEquals(0, speaker.said.size)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_SOFT, 15_000L))
        VoiceBus.post(OutdoorLevel.FALLBACK, "Droite.")
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `coupe abandonne l attente generalisee`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertTrue(VoiceBus.post(OutdoorLevel.STAIRS, "5 marches."))

        VoiceBus.onSpeechIdle(cut = true)

        assertEquals(1, speaker.said.size)
        VoiceBus.post(OutdoorLevel.FALLBACK, "Droite.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `pendant une parole le plus prioritaire attend la fin du mot`() {
        VoiceBus.post(OutdoorLevel.CURB, "bordure")
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.HUMAN, "personne encore")

        // Rien ne coupe : la bordure va au bout, le repli est jeté (sous le
        // rang), et le dernier humain remplace le précédent en place unique.
        assertEquals(listOf(NoCutFakeSpeaker.Said(OutdoorLevel.CURB, "bordure")), speaker.said)

        VoiceBus.onSpeechIdle()
        assertEquals(
            listOf(
                NoCutFakeSpeaker.Said(OutdoorLevel.CURB, "bordure"),
                NoCutFakeSpeaker.Said(OutdoorLevel.HUMAN, "personne encore"),
            ),
            speaker.said,
        )
    }

    @Test
    fun `verdict 100 attend la fin du 101, clip garde`() {
        VoiceBus.post(CrossingLevels.HUMAN, "personne")
        VoiceBus.post(CrossingLevels.VERDICT, "Don't cross.", "verdict_dont_cross")
        VoiceBus.post(CrossingLevels.HUMAN, "personne encore")

        // Le 101 va au bout ; le verdict attend en place unique (clip gardé) ;
        // le second 101 est sous le rang de l'attente (jeté, jamais redit).
        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        assertEquals(
            listOf(
                NoCutFakeSpeaker.Said(CrossingLevels.HUMAN, "personne"),
                NoCutFakeSpeaker.Said(CrossingLevels.VERDICT, "Don't cross.", "verdict_dont_cross"),
            ),
            speaker.said,
        )
    }

    @Test
    fun `trottoir passe devant les escaliers en attente`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertTrue(VoiceBus.post(OutdoorLevel.STAIRS, "5 marches."))
        assertTrue(VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche."))

        assertEquals(1, speaker.said.size)
        VoiceBus.onSpeechIdle()
        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.SIDEWALK, speaker.said[1].level)
    }
}
