package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.test.runTest
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
        listOf(
            OutdoorLevel.CAR_MOVING, OutdoorLevel.HUMAN, OutdoorLevel.GPS_REMINDER,
            OutdoorLevel.FENCE, OutdoorLevel.CURB, OutdoorLevel.POLE,
            OutdoorLevel.CROSSWALK, OutdoorLevel.CHAT, OutdoorLevel.SIDEWALK,
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
    fun `escaliers attendent la parole gps en cours`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Tournez à droite.")
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        assertEquals(2, speaker.said.size)
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
    fun `escaliers re-detectes dernier gagnant`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "4 marches.")

        assertEquals(2, speaker.said.size)
    }

    private fun rig(): ObstacleWatchers =
        ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())

    /** Lecture centrée 640×480 : boîte de [largeur] px à [meters] m. */
    private fun lecture(label: String, largeur: Float, meters: Float): DistanceReading {
        val hauteur = largeur * 0.6f
        return DistanceReading(
            -1, label, meters, meters,
            listOf(320f - largeur / 2f, 240f - hauteur / 2f, 320f + largeur / 2f, 240f + hauteur / 2f),
        )
    }

    private fun etat(frames: Int, reading: DistanceReading) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, listOf(reading)),
    )

    @Test
    fun `voiture tue pendant les escaliers sans consommer son tour`() = runTest {
        VoiceBus.post(OutdoorLevel.STAIRS, "Escalier montant.")
        val guetteurs = rig()
        val t0 = System.currentTimeMillis()

        obstacleFrame(etat(1, lecture("car", 100f, 8f)), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(2, lecture("car", 120f, 7f)), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(3, lecture("car", 145f, 6f)), { SpeechLanguage.Fr }, guetteurs)

        // La 3e frame confirmerait (3 frames croissantes = mouvement parlé
        // hors escaliers) : tue par les escaliers en cours, rien de posté.
        assertEquals(1, speaker.said.size)
        // Tour d'antirépétition intact (le bus jetterait le vœu, tour mangé) :
        // la voiture reparle dès la fin des marches, côté bus.
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CAR_MOVING, t0))
    }
}
