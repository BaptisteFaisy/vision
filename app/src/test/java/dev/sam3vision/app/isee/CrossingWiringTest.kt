package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.NoLightGate
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePhase
import dev.sam3vision.core.traverse.TraverseVeto
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Porte-parole faux : (clip, texte, séquence) enregistrés. */
private class ApproachFakeSpeaker : VoiceBusSpeaker {
    val calls = mutableListOf<Triple<String?, String, List<String>>>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        calls += Triple(clipId, text, clips)
    }

    override fun cut() {
    }
}

class CrossingWiringTest {

    private fun obj(label: String, box: List<Float>, score: Float = 0.8f) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    @Test
    fun `mode coupe libelle OFF`() {
        assertEquals("Crossing: OFF", crossingButtonLabel(false, null))
        assertEquals("Crossing: OFF", crossingButtonLabel(false, CrossingState.Green))
    }

    @Test
    fun `collecte - sans boite, pas de crop`() {
        // Pas de feu en vue : la collecte ne journalise rien (le rappel avec
        // boîte exige le décodage Bitmap, sur appareil seul).
        var calls = 0
        val light = crossingLightOf(LiveFrame(100, 100, 0L, emptyList()), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertEquals(0, calls)
    }

    @Test
    fun `mode actif sans lecture dit rouge, binaire`() {
        assertEquals("Crossing: RED", crossingButtonLabel(true, null))
    }

    @Test
    fun `aucun libelle ne pose de question`() {
        // Bascule manuelle, plus aucune demande : l'ecran ne demande jamais << cross? >>.
        listOf(
            crossingButtonLabel(false, null),
            crossingButtonLabel(false, CrossingState.Green),
            crossingButtonLabel(true, null),
            crossingButtonLabel(true, CrossingState.Red),
            crossingButtonLabel(true, CrossingState.Green),
        ).forEach { assertFalse(it.contains("?")) }
    }

    @Test
    fun `mode actif libelle l etat du feu`() {
        assertEquals("Crossing: GREEN", crossingButtonLabel(true, CrossingState.Green))
        assertEquals("Crossing: RED", crossingButtonLabel(true, CrossingState.Red))
    }

    @Test
    fun `bascule traversee garde les memes prompts, memes masques`() = runTest {
        val (probe, _) = probeHarness(this)
        val before = probe.state.value.conceptsSent
        probe.setCrossingActive(Settings.Default, true)
        assertTrue(probe.state.value.crossingActive)
        assertEquals(before, probe.state.value.conceptsSent)
        probe.setCrossingActive(Settings.Default, false)
        assertFalse(probe.state.value.crossingActive)
        assertEquals(before, probe.state.value.conceptsSent)
    }

    @Test
    fun `mode coupe ne publie ni phrase ni phase`() {
        // Sortie avant toute lecture : la sonde reste muette et le bandeau absent.
        val patch = crossingPatchOf(
            CrossingAnnouncer(), TraverseVeto(), false,
            LiveFrame(100, 100, 0L, emptyList()), LensSpec.Default, "", 0L, SpeechLanguage.Fr,
        )
        assertNull(patch)
    }

    @Test
    fun `patch actif - etat confirme rouge des la premiere frame`() {
        // Pas de boîte en vue : lecture rouge, rien d'annoncé → affiché rouge.
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val frame = LiveFrame(100, 100, 0L, emptyList())
        val patch = crossingPatchOf(
            announcer, veto, true, frame, LensSpec.Default, "", 0L, SpeechLanguage.Fr,
        )

        assertNull(patch?.cue)
        assertEquals(CrossingState.Red, patch?.state)
        assertNull(patch?.box)
    }

    @Test
    fun `patch actif - le rouge annonce parle a la deuxieme frame`() {
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val frame = LiveFrame(100, 100, 0L, emptyList())
        crossingPatchOf(announcer, veto, true, frame, LensSpec.Default, "", 0L, SpeechLanguage.Fr)
        val patch = crossingPatchOf(
            announcer, veto, true, frame, LensSpec.Default, "", 200L, SpeechLanguage.Fr,
        )

        assertEquals("Rouge.", patch?.cue?.phrase)
        assertEquals(CrossingState.Red, patch?.state)
    }

    @Test
    fun `vert sans boite - jamais une decision verte`() {
        // Fail-closed (ex-`boxOf()` null de la pompe locale) : seul un vert
        // AVEC boîte traverse ; seuils inchangés, c'est l'interprétation
        // qui verrouille, pas l'arbre ni la porte.
        assertFalse(CrossingLight(null, CrossingState.Green).greenForDecision)
        assertFalse(CrossingLight(null, CrossingState.Red).greenForDecision)
        assertFalse(CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.2f), CrossingState.Red).greenForDecision)
        assertTrue(CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.2f), CrossingState.Green).greenForDecision)
    }

    @Test
    fun `fusion deux feux - accord garde, desaccord attend`() {
        assertEquals(CrossingState.Red, combineLightStates(CrossingState.Red, CrossingState.Red))
        assertEquals(CrossingState.Green, combineLightStates(CrossingState.Green, CrossingState.Green))
        assertEquals(CrossingState.Red, combineLightStates(CrossingState.Red, CrossingState.Green))
        assertEquals(CrossingState.Red, combineLightStates(CrossingState.Green, CrossingState.Red))
    }

    @Test
    fun `signal - le PRET vert dit pret, pas traversez`() {
        val cue = CrossingCue("Vert.", false, CrossingState.Green)
        val ready = crossingCueOf(cue, TraversePhase.PRET, SpeechLanguage.Fr)
        assertEquals("Prêt.", ready?.phrase)
        assertEquals(CrossingState.Green, ready?.state)
        assertFalse(ready?.urgent ?: true)
    }

    @Test
    fun `signal - hors PRET vert, phrase inchangee, silence garde`() {
        val green = CrossingCue("Vert.", false, CrossingState.Green)
        assertEquals(
            "Vert.",
            crossingCueOf(green, TraversePhase.TRAVERSER, SpeechLanguage.Fr)?.phrase,
        )
        val red = CrossingCue("Rouge.", true, CrossingState.Red)
        assertEquals(
            "Rouge.",
            crossingCueOf(red, TraversePhase.PRET, SpeechLanguage.Fr)?.phrase,
        )
        assertNull(crossingCueOf(null, TraversePhase.PRET, SpeechLanguage.Fr))
    }

    @Test
    fun `signal - la contradiction remplace la phrase, urgence gardee`() {
        val cue = CrossingCue("Rouge.", true, CrossingState.Red)
        val out = crossingCueOf(cue, TraversePhase.ATTENDRE, SpeechLanguage.Fr, contradiction = true)
        assertEquals("Feux mixtes.", out?.phrase)
        assertEquals(CrossingState.Red, out?.state)
        assertTrue(out?.urgent ?: false)
    }

    @Test
    fun `sans feu pieton - rouge, sans boite, pas de collecte`() {
        // Objets non-feu seuls : rien à lire → rouge, boîte nulle, rien à
        // journaliser. Les feux voiture ne sont jamais lus (31/10).
        var calls = 0
        val frame = LiveFrame(640, 480, 0L, listOf(SegObject("car", 0.9f, listOf(0f, 0f, 200f, 200f), "")))
        val light = crossingLightOf(frame, "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNull(light.box)
        assertEquals(0, calls)
    }

    @Test
    fun `boite lue mais image illisible - rouge, boite gardee, pas de collecte`() {
        // JPEG vide : le crop échoue avant tout décodage (JVM-safe) → rouge,
        // la boîte reste pour l'overlay, rien à journaliser.
        var calls = 0
        val objects = listOf(SegObject("pedestrian traffic light", 0.9f, listOf(10f, 10f, 100f, 200f), ""))
        val light = crossingLightOf(LiveFrame(640, 480, 0L, objects), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNotNull(light.box)
        assertEquals(0, calls)
    }

    @Test
    fun `poteau seul sans decodage - rouge, sans boite, branche sans-feu intacte`() {
        // Repli poteau sans JPEG : pas de lampe vérifiable → boîte nulle
        // (le sans-feu compte les nulles), rouge, rien à journaliser.
        var calls = 0
        val objects = listOf(SegObject("pole", 0.9f, listOf(96f, 40f, 104f, 380f), ""))
        val light = crossingLightOf(LiveFrame(640, 480, 0L, objects), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNull(light.box)
        assertEquals(0, calls)
    }

    @Test
    fun `feu prioritaire sur poteau meme sans decodage`() {
        // Les deux boîtes en vue : le feu gagne, le repli ne sert pas.
        val objects = listOf(
            SegObject("pole", 0.99f, listOf(96f, 40f, 104f, 380f), ""),
            SegObject("pedestrian traffic light", 0.60f, listOf(10f, 10f, 100f, 200f), ""),
        )
        val light = crossingLightOf(LiveFrame(640, 480, 0L, objects), "")

        assertEquals(CrossingState.Red, light.state)
        assertEquals(listOf(10f / 640f, 10f / 480f, 100f / 640f, 200f / 480f), light.box)
    }

    @Test
    fun `dimensions inconnues - rouge sans boite`() {
        // Taille de frame inconnue : aucune boîte normalisable → rouge, null.
        var calls = 0
        val objects = listOf(SegObject("pedestrian traffic light", 0.9f, listOf(10f, 10f, 100f, 200f), ""))
        val light = crossingLightOf(LiveFrame(0, 0, 0L, objects), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNull(light.box)
        assertEquals(0, calls)
    }

    @Test
    fun `absence de feu confirmee - le veto trafic survit au patch`() {
        // A9 §3 D7 : dans la branche sans-feu, le 5e argument positionnel est
        // `noLight` et le veto restait à sa valeur par défaut (`false`) alors
        // que la voiture proche le rendait vrai — `carVeto` perdu, donc
        // « Cross. » possible avec un bus qui arrive. Le veto doit traverser.
        val gate = NoLightGate()
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        // Voiture qui approche (boîte qui grossit : ni garée ni immobile) et
        // très proche (≤ 3 m au sténopé) : veto franc dès la première frame.
        val boxes = listOf(
            listOf(0f, 0f, 20f, 20f),
            listOf(0f, 0f, 30f, 30f),
            listOf(0f, 0f, 40f, 40f),
            listOf(0f, 0f, 55f, 55f),
        )
        var patch: CrossingPatch? = null
        repeat(NoLightGate.NO_LIGHT_MIN_FRAMES) { i ->
            val frame = LiveFrame(100, 100, 0L, listOf(SegObject("car", 0.9f, boxes[i], "")))
            patch = crossingPatchOf(
                announcer, veto, true, frame, LensSpec.Default, "", i * 200L,
                SpeechLanguage.Fr, noLightGate = gate,
            )
        }
        val last = patch!!
        assertTrue("absence de feu confirmée", last.noLight)
        assertNull("le feu se tait", last.cue)
        assertEquals(CrossingState.Red, last.state)
        assertTrue("voiture proche : veto conservé hors branche feu", last.veto)
    }

    @Test
    fun `repli approche avec distance parle la direction seule`() {
        // R1 muette : "Left, 2 meters." ne matche aucun texte du catalogue
        // (pas d'atome "mètre") et tombait en `sans-clip` — le repli parle
        // l'atome direction avec les clips existants (arbitrage user).
        assertEquals(listOf(IseeClipsEn.GO_LEFT), crossingApproachClips("Left, 2 meters."))
        assertEquals(listOf(IseeClipsEn.GO_RIGHT), crossingApproachClips("Right, 1 meter."))
        assertEquals(listOf(IseeClipsEn.AHEAD), crossingApproachClips("Ahead, 50 centimeters."))
    }

    @Test
    fun `repli approche sans distance garde le comportement existant`() {
        // Sans distance, la phrase se résout déjà au catalogue (clip parlé
        // par la branche clip du bus) : le repli ne s'en mêle pas.
        assertEquals(emptyList<String>(), crossingApproachClips("Left."))
        assertEquals(emptyList<String>(), crossingApproachClips("Right."))
        assertEquals(emptyList<String>(), crossingApproachClips("Ahead."))
        assertEquals(IseeClipsEn.GO_LEFT, IseeClipsEn.idForText("Left."))
        assertEquals(IseeClipsEn.AHEAD, IseeClipsEn.idForText("Ahead."))
    }

    @Test
    fun `repli approche ignore le francais et l inconnu`() {
        // Pack EN seul : le français reste muet (jamais de synthèse ici),
        // l'inconnu aussi (jamais de mensonge, jamais de devinette).
        assertEquals(emptyList<String>(), crossingApproachClips("Gauche, 2 mètres."))
        assertEquals(emptyList<String>(), crossingApproachClips("Devant."))
        assertEquals(emptyList<String>(), crossingApproachClips("Cross."))
        assertEquals(emptyList<String>(), crossingApproachClips(""))
    }

    @Test
    fun `voix annonce approche avec distance poste clips direction, texte inchange`() = runTest {
        val speaker = ApproachFakeSpeaker()
        AntiRepeat.reset()
        VoiceBus.reset()
        CrossingLateralWatch.reset()
        VoiceBus.speaker = speaker
        try {
            observeCrossingVoice(backgroundScope)
            val box = listOf(10f, 100f, 100f, 500f)
            val objects = listOf(obj("crosswalk", box))
            val readings = listOf(reading("crosswalk", 2.0f, box))
            CrossingLateralWatch.update(objects, 512, 1138, readings, null, LensSpec.Default, 0L, SpeechLanguage.En)
            runCurrent()
            CrossingLateralWatch.update(objects, 512, 1138, readings, null, LensSpec.Default, 200L, SpeechLanguage.En)
            runCurrent()

            assertTrue(
                "annonce audible avec [direction]",
                speaker.calls.any { it == Triple(null, "Left, 2 meters.", listOf(IseeClipsEn.GO_LEFT)) },
            )
            assertTrue(
                "jamais d'annonce avec distance sans clips (le bug R1)",
                speaker.calls.none { it.second == "Left, 2 meters." && it.third.isEmpty() },
            )
        } finally {
            VoiceBus.speaker = null
            VoiceBus.reset()
            AntiRepeat.reset()
            CrossingLateralWatch.reset()
        }
    }

    @Test
    fun `voix annonce approche sans distance poste sans clips, existant`() = runTest {
        val speaker = ApproachFakeSpeaker()
        AntiRepeat.reset()
        VoiceBus.reset()
        CrossingLateralWatch.reset()
        VoiceBus.speaker = speaker
        try {
            observeCrossingVoice(backgroundScope)
            val box = listOf(10f, 100f, 100f, 500f)
            val objects = listOf(obj("crosswalk", box))
            CrossingLateralWatch.update(objects, 512, 1138, emptyList(), null, LensSpec.Default, 0L, SpeechLanguage.En)
            runCurrent()
            CrossingLateralWatch.update(objects, 512, 1138, emptyList(), null, LensSpec.Default, 200L, SpeechLanguage.En)
            runCurrent()

            assertTrue(
                "direction seule postée sans séquence (branche clip, existant)",
                speaker.calls.any { it == Triple(null, "Left.", emptyList<String>()) },
            )
        } finally {
            VoiceBus.speaker = null
            VoiceBus.reset()
            AntiRepeat.reset()
            CrossingLateralWatch.reset()
        }
    }
}
