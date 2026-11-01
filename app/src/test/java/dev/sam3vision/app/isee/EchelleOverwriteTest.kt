package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.VehicleStateCue
import dev.sam3vision.core.traverse.VehicleStateWatcher
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : parole scriptée, dits enregistrés. */
private class FakeSpeaker(var idleValue: Boolean = true) : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String, val clipId: String?)

    val said = mutableListOf<Said>()
    var cuts = 0

    override fun idle(): Boolean = idleValue

    override fun say(level: Int, text: String, clipId: String?) {
        said += Said(level, text, clipId)
    }

    override fun cut() {
        cuts++
    }
}

/** Pilote TTS système muet : la sécurité ne parle pas dans ce banc. */
private class SilentDriver : TtsDriver {
    override val speaking: Boolean get() = false
    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean = true
    override fun stop() = Unit
    override fun shutdown() = Unit
}

/**
 * Échelle dehors SPEC v4 (0 à 12) + Traversée (100 à 102) : ordre des
 * niveaux, décision par frame (le plus petit gagne), vœux par guetteur,
 * antirépétition, écrasement du bus et flux obstacles vers le bus.
 */
class EchelleOverwriteTest {

    private val speaker = FakeSpeaker()

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

    // Échelle.

    @Test
    fun `niveaux dehors 0 a 12 dans l ordre de la spec`() {
        assertEquals(0, OutdoorLevel.CAR_MOVING)
        assertEquals(1, OutdoorLevel.USER_SPEECH)
        assertEquals(2, OutdoorLevel.HUMAN)
        assertEquals(3, OutdoorLevel.GPS_REMINDER)
        assertEquals(4, OutdoorLevel.FENCE)
        assertEquals(5, OutdoorLevel.CURB)
        assertEquals(6, OutdoorLevel.STAIRS)
        assertEquals(7, OutdoorLevel.POLE)
        assertEquals(8, OutdoorLevel.CROSSWALK)
        assertEquals(9, OutdoorLevel.CHAT)
        assertEquals(10, OutdoorLevel.SIDEWALK)
        assertEquals(11, OutdoorLevel.GPS_EVENT)
        assertEquals(12, OutdoorLevel.FALLBACK)
    }

    @Test
    fun `niveaux traversee verdict puis humains puis bordures`() {
        assertEquals(100, CrossingLevel.VERDICT)
        assertEquals(101, CrossingLevel.HUMAN)
        assertEquals(102, CrossingLevel.CURB)
    }

    // Décision par frame.

    @Test
    fun `le plus petit niveau gagne, les autres ecrases`() {
        val winner = decideFrame(
            listOf(
                VoiceWish(OutdoorLevel.CURB, "bordure", AntiRepeat.KEY_CURB),
                VoiceWish(OutdoorLevel.HUMAN, "personne", AntiRepeat.KEY_HUMAN_SOFT),
                VoiceWish(OutdoorLevel.CAR_MOVING, "voiture", AntiRepeat.KEY_CAR_MOVING),
            ),
        )

        assertEquals(OutdoorLevel.CAR_MOVING, winner?.level)
        assertEquals("voiture", winner?.text)
    }

    @Test
    fun `nuls et blancs ignores, silence si rien`() {
        assertNull(decideFrame(listOf(null, null)))
        assertNull(decideFrame(listOf(VoiceWish(OutdoorLevel.CURB, "  ", AntiRepeat.KEY_CURB))))
        assertEquals(
            "seul",
            decideFrame(listOf(null, VoiceWish(OutdoorLevel.SIDEWALK, "seul", AntiRepeat.KEY_SIDEWALK)))?.text,
        )
    }

    // Vœux par guetteur.

    @Test
    fun `vehicule en mouvement niveau 0 avec clip, arret muet`() {
        val moving = vehicleWish(VehicleStateCue("Voiture en mouvement.", meters = 5f, moving = true))

        assertNotNull(moving)
        assertEquals(OutdoorLevel.CAR_MOVING, moving?.level)
        assertEquals(AntiRepeat.KEY_CAR_MOVING, moving?.key)
        assertEquals("vehicle_car_moving", moving?.clipId)

        assertNull(vehicleWish(VehicleStateCue("Voiture à l'arrêt.", meters = 5f, moving = false)))
        assertNull(vehicleWish(null))
    }

    @Test
    fun `vehicule pluriel niveau 0 sans clip`() {
        val plural = vehicleWish(VehicleStateCue("2 vehicles moving.", meters = 5f, moving = true, movingCount = 2))

        assertEquals(OutdoorLevel.CAR_MOVING, plural?.level)
        assertNull(plural?.clipId)
    }

    @Test
    fun `personne douce niveau 2, mot imminent meme niveau en clip`() {
        val soft = hazardWish(HazardCue("Attention à la personne.", meters = 3f, kind = HazardKind.PERSON))

        assertEquals(OutdoorLevel.HUMAN, soft?.level)
        assertEquals(AntiRepeat.KEY_HUMAN_SOFT, soft?.key)
        assertNull(soft?.clipId)

        val word = GuidanceWord.fromCap(dev.sam3vision.core.live.SteeringCap.Right).text(SpeechLanguage.Fr)
        val imminent = hazardWish(HazardCue(word, meters = 1f, urgent = true, kind = HazardKind.PERSON, antiRepeatKey = AntiRepeat.KEY_HUMAN_IMMINENT))

        assertEquals(OutdoorLevel.HUMAN, imminent?.level)
        assertEquals(AntiRepeat.KEY_HUMAN_IMMINENT, imminent?.key)
        assertEquals(GuidanceWord.clipIdFor(word), imminent?.clipId)
        assertNotNull(imminent?.clipId)
    }

    @Test
    fun `barriere niveau 4, divers niveau 7`() {
        assertEquals(
            OutdoorLevel.FENCE,
            hazardWish(HazardCue("Attention à la barrière.", meters = 2f, kind = HazardKind.BARRIER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE))?.level,
        )
        assertEquals(
            AntiRepeat.KEY_FENCE,
            hazardWish(HazardCue("Attention à la barrière.", meters = 2f, kind = HazardKind.BARRIER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE))?.key,
        )
        assertEquals(
            OutdoorLevel.POLE,
            hazardWish(HazardCue("Attention au lampadaire.", meters = 2f, kind = HazardKind.OTHER, level = OutdoorLevel.POLE, antiRepeatKey = AntiRepeat.KEY_POLE))?.level,
        )
        assertNull(hazardWish(null))
    }

    @Test
    fun `traversee personne 101, barriere et divers muets`() {
        val person = hazardWish(
            HazardCue("Attention à la personne.", meters = 3f, kind = HazardKind.PERSON),
            crossingActive = true,
        )

        assertEquals(CrossingLevel.HUMAN, person?.level)
        assertNull(
            hazardWish(
                HazardCue("Attention à la barrière.", meters = 2f, kind = HazardKind.BARRIER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE),
                crossingActive = true,
            ),
        )
        assertNull(
            hazardWish(
                HazardCue("Attention au lampadaire.", meters = 2f, kind = HazardKind.OTHER, level = OutdoorLevel.POLE, antiRepeatKey = AntiRepeat.KEY_POLE),
                crossingActive = true,
            ),
        )
    }

    @Test
    fun `bordure 5 dehors, 102 en traversee`() {
        val cue = CurbCue("Bordure détectée.", meters = 2f, tier = CurbTier.FAR)

        assertEquals(OutdoorLevel.CURB, curbWish(cue)?.level)
        assertEquals(AntiRepeat.KEY_CURB, curbWish(cue)?.key)
        assertEquals(CrossingLevel.CURB, curbWish(cue, crossingActive = true)?.level)
        assertNull(curbWish(null))
    }

    // Antirépétition.

    @Test
    fun `fenetres de la spec par cle`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CAR_MOVING, 0L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_CAR_MOVING, 3_999L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CAR_MOVING, 4_000L))

        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_SOFT, 0L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_SOFT, 1_999L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_SOFT, 2_000L))

        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_SIDEWALK, 0L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_SIDEWALK, 9_999L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_SIDEWALK, 10_000L))

        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_VERDICT, 0L))
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_VERDICT, 2_999L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_VERDICT, 3_000L))
    }

    @Test
    fun `imminent immediat, cles inconnues jamais tues`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_IMMINENT, 0L))
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_HUMAN_IMMINENT, 0L))
        assertTrue(AntiRepeat.allow("inconnue", 0L))
        assertTrue(AntiRepeat.allow("inconnue", 0L))
    }

    // Bus : écrasement.

    @Test
    fun `au repos tout niveau parle, rien de branche rien ne casse`() {
        VoiceBus.speaker = null
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")

        VoiceBus.speaker = speaker
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        VoiceBus.post(OutdoorLevel.FALLBACK, "  ")

        assertEquals(listOf(FakeSpeaker.Said(OutdoorLevel.FALLBACK, "repli", null)), speaker.said)
    }

    @Test
    fun `pendant une parole seul un niveau plus petit ou egal coupe`() {
        speaker.idleValue = false
        VoiceBus.post(OutdoorLevel.CURB, "bordure")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "trottoir")
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.HUMAN, "personne encore")

        assertEquals(
            listOf(
                FakeSpeaker.Said(OutdoorLevel.CURB, "bordure", null),
                FakeSpeaker.Said(OutdoorLevel.HUMAN, "personne", null),
                FakeSpeaker.Said(OutdoorLevel.HUMAN, "personne encore", null),
            ),
            speaker.said,
        )
    }

    @Test
    fun `parole user tait tout sauf niveau 0`() {
        VoiceBus.userSpeaking(true)
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Voiture en mouvement.", "vehicle_car_moving")

        assertEquals(1, speaker.cuts)
        assertEquals(
            listOf(FakeSpeaker.Said(OutdoorLevel.CAR_MOVING, "Voiture en mouvement.", "vehicle_car_moving")),
            speaker.said,
        )

        VoiceBus.userSpeaking(false)
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `chat tient le niveau 9, repli ecrase reponse coupe`() {
        speaker.idleValue = false
        VoiceBus.chatSpeaking(true)
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")

        assertEquals(listOf(FakeSpeaker.Said(OutdoorLevel.HUMAN, "personne", null)), speaker.said)

        VoiceBus.chatSpeaking(false)
        speaker.idleValue = true
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `offre postee une fois puis tue par l antirepetition`() {
        val wish = VoiceWish(OutdoorLevel.CURB, "bordure", AntiRepeat.KEY_CURB)

        assertTrue(offer(wish, nowMs = 0L))
        assertFalse(offer(wish, nowMs = 1_000L))
        assertTrue(offer(wish, nowMs = 2_000L))
        assertFalse(offer(null, nowMs = 3_000L))

        assertEquals(2, speaker.said.size)
    }

    // Flux obstacles vers le bus.

    private fun tempDir(): File = Files.createTempDirectory("echelle").toFile()

    private fun rig(): Triple<ProximityVoice, SafetyVoice, ObstacleWatchers> {
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default },
        )
        val safety = SafetyVoice(
            player = SpeechPlayer(File(tempDir(), "securite")),
            system = SystemTtsEngine(SilentDriver()),
            settings = { Settings.Default },
            stopCurrent = {},
        )
        return Triple(voice, safety, ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher()))
    }

    /** Lecture centrée 640×480 : boîte de [largeur] px à [meters] m. */
    private fun lecture(label: String, largeur: Float, meters: Float): DistanceReading {
        val hauteur = largeur * 0.6f
        return DistanceReading(
            -1, label, meters, meters,
            listOf(320f - largeur / 2f, 240f - hauteur / 2f, 320f + largeur / 2f, 240f + hauteur / 2f),
        )
    }

    private fun etat(frames: Int, reading: DistanceReading, traversee: Boolean = false) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, listOf(reading)),
        crossingActive = traversee,
    )

    @Test
    fun `personne confirmee postee niveau 2`() = runTest {
        val (voice, safety, guetteurs) = rig()
        val doux = Mutex()
        val personne = lecture("person", 100f, 3f)

        obstacleFrame(etat(1, personne), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        assertTrue(speaker.said.isEmpty())
        obstacleFrame(etat(2, personne), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.HUMAN, speaker.said.single().level)
        assertTrue(speaker.said.single().text.startsWith("Attention à la personne"))
        assertNull(speaker.said.single().clipId)
    }

    @Test
    fun `personne imminente postee niveau 2 en clip des la premiere frame`() = runTest {
        val (voice, safety, guetteurs) = rig()

        obstacleFrame(
            etat(1, lecture("person", 200f, 1.5f)), this, voice, { true },
            { SpeechLanguage.Fr }, safety, guetteurs, Mutex(),
        )

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.HUMAN, speaker.said.single().level)
        assertEquals(GuidanceWord.clipIdFor(speaker.said.single().text), speaker.said.single().clipId)
        assertNotNull(speaker.said.single().clipId)
    }

    @Test
    fun `voiture qui approche postee niveau 0 avec clip`() = runTest {
        val (voice, safety, guetteurs) = rig()
        val doux = Mutex()

        obstacleFrame(etat(1, lecture("car", 100f, 8f)), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(2, lecture("car", 120f, 7f)), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(3, lecture("car", 145f, 6f)), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)

        assertEquals(1, speaker.said.size)
        assertEquals(OutdoorLevel.CAR_MOVING, speaker.said.single().level)
        assertEquals("Voiture en mouvement.", speaker.said.single().text)
        assertEquals("vehicle_car_moving", speaker.said.single().clipId)
    }

    @Test
    fun `voiture a l arret muette, rien de poste`() = runTest {
        val (voice, safety, guetteurs) = rig()
        val doux = Mutex()
        val arret = lecture("car", 100f, 4f)

        obstacleFrame(etat(1, arret), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(2, arret), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(3, arret), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)

        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `traversee personne 101, voiture couverte par le verdict`() = runTest {
        val (voice, safety, guetteurs) = rig()
        val doux = Mutex()
        val personne = lecture("person", 100f, 3f)

        obstacleFrame(etat(1, personne, traversee = true), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(2, personne, traversee = true), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)

        assertEquals(1, speaker.said.size)
        assertEquals(CrossingLevel.HUMAN, speaker.said.single().level)

        val (voix2, securite2, guetteurs2) = rig()
        val voiture = lecture("car", 100f, 5f)
        obstacleFrame(etat(1, voiture, traversee = true), this, voix2, { true }, { SpeechLanguage.Fr }, securite2, guetteurs2, doux)
        obstacleFrame(etat(2, voiture, traversee = true), this, voix2, { true }, { SpeechLanguage.Fr }, securite2, guetteurs2, doux)

        assertEquals(1, speaker.said.size)
    }
}
