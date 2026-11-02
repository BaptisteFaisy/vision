package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.live.AntiRepeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class StairsFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * Escaliers prioritaires (ordre user du 02/11/2026, VOIX-DANGER § 42 —
 * amende l'ordre du matin, § 40) : des escaliers en cours (niveau 6)
 * vont au bout — tout le monde attend (niveau 0, humains, GPS,
 * grillages, bordures, chat, repli, Traversée) ; en retour les
 * escaliers coupent toute parole en cours. Seule la parole du user
 * passe devant. L'esquive parle dans le silence entre deux comptes.
 */
class StairsUncuttableTest {

    private val speaker = StairsFakeSpeaker()

    @Before
    fun brancher() {
        ProbeBackgroundMotion.reset()
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
        ProbeBackgroundMotion.reset()
    }

    @Test
    fun `niveau 0 attend la fin des escaliers au lieu de couper`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "Escalier montant.")
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")

        assertEquals(listOf(StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "Escalier montant.")), speaker.said)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `verdict traversee attend la fin des escaliers`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(CrossingLevels.VERDICT, "Cross.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(CrossingLevels.VERDICT, "Cross.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `tous les niveaux attendent la fin des escaliers`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        // Le trottoir n'attend plus : absolu depuis le § 45 (il coupe le compte).
        listOf(
            OutdoorLevel.CAR_MOVING, OutdoorLevel.HUMAN, OutdoorLevel.GPS_REMINDER,
            OutdoorLevel.FENCE, OutdoorLevel.CURB, OutdoorLevel.POLE,
            OutdoorLevel.CROSSWALK, OutdoorLevel.CHAT,
            OutdoorLevel.GPS_EVENT, OutdoorLevel.FALLBACK,
        ).forEach { VoiceBus.post(it, "niveau $it.") }

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.FALLBACK, "Gauche.")
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertEquals(3, speaker.said.size)
    }

    @Test
    fun `escaliers coupent l humain et le chat en cours`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Attention, personne.")
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")

        assertEquals(2, speaker.said.size)

        VoiceBus.reset()
        VoiceBus.chatSpeaking(true)
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        assertEquals(3, speaker.said.size)
        VoiceBus.chatSpeaking(false)
    }

    @Test
    fun `esquive parle dans le silence entre deux comptes`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertEquals(1, speaker.said.size)

        // Fin du compte : le mot continu, re-posté à chaque frame,
        // parle au milieu du cycle.
        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertEquals(2, speaker.said.size)

        // Compte suivant : il coupe l'esquive en cours.
        VoiceBus.post(OutdoorLevel.STAIRS, "4 marches.")
        assertEquals(3, speaker.said.size)
    }

    @Test
    fun `escaliers attendent la parole du user`() {
        VoiceBus.userSpeaking(true)
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        assertEquals(0, speaker.said.size)

        VoiceBus.userSpeaking(false)
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `bus bloque sur escaliers se libere apres le delai`() {
        assertTrue(stairsBusStuck(OutdoorLevel.STAIRS, 4_001L, 0L))
        assertFalse(stairsBusStuck(OutdoorLevel.STAIRS, 4_000L, 0L))
        assertFalse(stairsBusStuck(OutdoorLevel.STAIRS, 1_000L, 500L))
        assertFalse(stairsBusStuck(OutdoorLevel.HUMAN, 99_999L, 0L))
    }

    @Test
    fun `escaliers coupent le mot de guidage en cours`() {
        // Ordre user du 02/11/2026 (top priorité sur « droite » / « gauche ») :
        // le respect mutuel GPS est levé pour les escaliers, comme pour la bordure.
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.STAIRS, "Stairs 2 m.")

        assertEquals(2, speaker.said.size)
        assertEquals(StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "Stairs 2 m."), speaker.said[1])

        VoiceBus.reset()
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")
        VoiceBus.post(OutdoorLevel.STAIRS, "Stairs 1 m.")
        assertEquals(4, speaker.said.size)
    }

    @Test
    fun `escaliers actifs jusqu a 2 s sans compte`() {
        val nowMs = System.currentTimeMillis()
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")

        assertTrue(VoiceBus.stairsActive(nowMs))
        assertTrue(VoiceBus.stairsActive(nowMs + 1_999L))
        assertTrue(VoiceBus.stairsActive(nowMs + 99_999L))

        VoiceBus.onSpeechIdle()
        assertFalse(VoiceBus.stairsActive(nowMs + 3_000L))
    }

    @Test
    fun `escaliers re-detectes dernier gagnant sans couper`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "4 marches.")

        // La phrase en cours va au bout ; la suivante attend son tour.
        assertEquals(1, speaker.said.size)
        VoiceBus.onSpeechIdle()
        assertEquals(
            listOf(
                StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "5 marches."),
                StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "4 marches."),
            ),
            speaker.said,
        )
    }

    @Test
    fun `un seul compte en attente, le dernier gagne`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "6 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "7 marches.")

        assertEquals(1, speaker.said.size)
        VoiceBus.onSpeechIdle()
        assertEquals(2, speaker.said.size)
        assertEquals(StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "7 marches."), speaker.said[1])
    }

    @Test
    fun `un compte perime ne se dit pas`() {
        val now = longArrayOf(1_000L)
        VoiceBus.clock = { now[0] }
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "6 marches.")

        now[0] = 3_000L
        VoiceBus.onSpeechIdle()

        assertEquals(1, speaker.said.size)
        // Le bus est rendu : le niveau 6 ne tait plus tout le monde.
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `une coupe abandonne le compte en attente`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "6 marches.")

        VoiceBus.onSpeechIdle(cut = true)

        assertEquals(1, speaker.said.size)
        // Le bus est rendu : le compte abandonné ne tait plus rien.
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `deux repos d affilee ne parlent pas deux fois`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "6 marches.")

        VoiceBus.onSpeechIdle()
        VoiceBus.onSpeechIdle()

        assertEquals(
            listOf(
                StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "5 marches."),
                StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "6 marches."),
            ),
            speaker.said,
        )
    }

    @Test
    fun `la parole du user abandonne le compte en attente`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "6 marches.")

        VoiceBus.userSpeaking(true)
        VoiceBus.userSpeaking(false)
        VoiceBus.onSpeechIdle()

        assertEquals(1, speaker.said.size)
    }

}
