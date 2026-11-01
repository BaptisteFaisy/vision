package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.VehicleStateWatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bout-en-bout bordure→bus : le vrai [obstacleFrame] branché sur un
 * porte-parole faux — une bordure valide (label `curb`, ≤ 3 m, centre du
 * couloir, bas de boîte en bande basse) confirmée sur 2 frames poste au
 * niveau 5 (« Bordure détectée… ») ; des lectures qu'aucun guetteur ne
 * revendique restent muettes. La ligne diagnostique `CurbVoice` y résume
 * la frame.
 */
class ObstacleCurbVoiceTest {

    /** Porte-parole faux : dits enregistrés. */
    private class FakeBusSpeaker : VoiceBusSpeaker {
        data class Said(val level: Int, val text: String)

        val said = mutableListOf<Said>()
        override fun idle(): Boolean = true
        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += Said(level, text)
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
    private fun etat(frames: Int, readings: List<DistanceReading>) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, readings),
    )

    @Test
    fun `bordure confirmee sur 2 frames = postee niveau 5`() {
        val guetteurs = ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(bordure())), { SpeechLanguage.Fr }, guetteurs)
        assertTrue(speaker.said.isEmpty())
        obstacleFrame(etat(frames = 2, listOf(bordure())), { SpeechLanguage.Fr }, guetteurs)

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.CURB, speaker.said.single().level)
        assertEquals("Bordure détectée, à midi.", speaker.said.single().text)
    }

    @Test
    fun `lectures non revendiquees = silence`() {
        val guetteurs = ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())

        obstacleFrame(etat(frames = 1, listOf(arbre())), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(frames = 2, listOf(arbre())), { SpeechLanguage.Fr }, guetteurs)

        assertTrue(speaker.said.isEmpty())
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
