package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bout-en-bout bordure→bus : le vrai [obstacleFrame] branché sur un
 * porte-parole faux — une bordure valide (label `curb`, ≤ 3 m, centre du
 * couloir, bas de boîte en bande basse) confirmée sur 2 frames poste au
 * niveau 6 (« Bordure détectée… ») ; des lectures qu'aucun guetteur ne
 * revendique restent muettes. La ligne diagnostique `CurbVoice` y résume
 * la frame.
 */
class ObstacleCurbVoiceTest {

    /** Porte-parole faux : dits enregistrés (texte + clips). */
    private class FakeBusSpeaker : VoiceBusSpeaker {
        data class Said(val level: Int, val text: String, val clips: List<String> = emptyList())

        val said = mutableListOf<Said>()
        override fun idle(): Boolean = true
        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += Said(level, text, clips)
        }

        override fun cut() = Unit
    }

    private val speaker = FakeBusSpeaker()

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

    /** Bordure valide : label `curb`, 2,5 m, centrée, bas en bande basse (640×480). */
    private fun bordure(meters: Float = 2.5f) = DistanceReading(
        -1, "curb", meters, meters, listOf(220f, 300f, 420f, 460f),
    )

    /** Passant sans bord, hors couloir : ni bordure, ni véhicule, ni danger parlé — aucun guetteur ne parle. */
    private fun passant() = DistanceReading(
        -1, "person", 5f, 5f, listOf(20f, 300f, 120f, 460f),
    )

    /** Arbre : aucun guetteur dehors ne le revendique — silence garanti. */
    private fun arbre() = DistanceReading(
        -1, "tree", 5f, 5f, listOf(220f, 300f, 420f, 460f),
    )

    /** État sonde minimal : [frames] vues, [readings] dans le calque distances. */
    private fun etat(frames: Int, readings: List<DistanceReading>, crossingActive: Boolean = false) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, readings),
        crossingActive = crossingActive,
    )

    /** Descente proche : plan `sidewalk` collé aux pieds mais borné en haut (sens Down, 640×480). */
    private fun descente(meters: Float = 0.8f) = DistanceReading(
        -1, "sidewalk", meters, meters, listOf(220f, 260f, 420f, 475f),
    )

    @Test
    fun `bordure confirmee sur 2 frames = postee niveau 6`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(bordure())), { SpeechLanguage.Fr }, guetteurs)
        assertTrue(speaker.said.isEmpty())
        obstacleFrame(etat(frames = 2, listOf(bordure())), { SpeechLanguage.Fr }, guetteurs)

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.CURB, speaker.said.single().level)
        assertEquals("Bordure détectée.", speaker.said.single().text)
    }

    @Test
    fun `lectures non revendiquees = silence`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(arbre())), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(arbre())), { SpeechLanguage.Fr }, guetteurs)

        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `montee sous 1m confirmee = boucle curb up niveau 6 en clip`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)
        assertTrue(speaker.said.isEmpty())
        obstacleFrame(etat(frames = 2, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.CURB, speaker.said.single().level)
        assertEquals(IseeClipsEn.CURB_UP_TEXT, speaker.said.single().text)
        assertEquals(listOf(IseeClipsEn.CURB_UP), speaker.said.single().clips)
    }

    @Test
    fun `boucle ne reposte pas sur bordure en cours`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())
        obstacleFrame(etat(frames = 1, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)
        assertEquals(1, speaker.said.size)

        obstacleFrame(etat(frames = 3, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 4, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)

        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `boucle reparle au repos, en permanence`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())
        obstacleFrame(etat(frames = 1, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)
        VoiceBus.onSpeechIdle()

        obstacleFrame(etat(frames = 3, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, guetteurs)

        assertEquals(2, speaker.said.size)
        assertTrue(speaker.said.all { it.text == IseeClipsEn.CURB_UP_TEXT })
    }

    @Test
    fun `1m pile = pas de boucle`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(bordure(1.0f))), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(bordure(1.0f))), { SpeechLanguage.Fr }, guetteurs)

        assertTrue(speaker.said.none { it.text == IseeClipsEn.CURB_UP_TEXT })
    }

    @Test
    fun `descente sous 1m = pas de boucle`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(descente())), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(descente())), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 3, listOf(descente())), { SpeechLanguage.Fr }, guetteurs)

        assertTrue(speaker.said.none { it.text == IseeClipsEn.CURB_UP_TEXT })
    }

    @Test
    fun `traversee et dedans = pas de boucle`() {
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(bordure(0.8f)), crossingActive = true), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(bordure(0.8f)), crossingActive = true), { SpeechLanguage.Fr }, guetteurs)
        assertTrue("traversée", speaker.said.isEmpty())

        val dedans = ObstacleWatchers(CurbWatcher(), HazardWatcher())
        obstacleFrame(etat(frames = 1, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, dedans, { PictoPlaceMode.DEDANS })
        obstacleFrame(etat(frames = 2, listOf(bordure(0.8f))), { SpeechLanguage.Fr }, dedans, { PictoPlaceMode.DEDANS })
        assertTrue("dedans", speaker.said.isEmpty())
    }

    @Test
    fun `ligne diagnostique resume lectures signal et phrase`() {
        val readings = listOf(bordure(), passant())
        val signal = CurbSignal.of(readings, 640, 480)

        val line = curbVoiceLine(readings, signal, "Bordure détectée, à midi.", crossingActive = false)

        assertTrue(line.contains("lectures=2"))
        assertTrue(line.contains("curb/2.5m/bas=460.0"))
        assertTrue(line.contains("signal=true/2.5m/FAR"))
        assertTrue(line.contains("phrase=Bordure détectée, à midi."))
        assertTrue(line.contains("traversee=false"))
    }
}
