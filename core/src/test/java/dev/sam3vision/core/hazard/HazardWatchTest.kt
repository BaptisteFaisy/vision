package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Danger proche dans le couloir : détecté, le plus proche nommé, le reste muet. */
class HazardWatchTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Danger centré (frame 512×1138). */
    private fun danger(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    @Test
    fun `barriere proche dans le couloir = detectee et nommee`() {
        val signal = HazardSignal.of(listOf(danger("barrier", 0.8f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.8f, signal.meters!!, 0.001f)
        assertEquals("barrier", signal.label)
        assertEquals(0.5f, signal.centerX!!, 0.001f)
    }

    @Test
    fun `l'heure situe l'annonce`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "barrier", centerX = 0.8f)
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière, à 2 heures.", cue!!.phrase)
    }

    @Test
    fun `plus proche gagne`() {
        val signal = HazardSignal.of(
            listOf(danger("barrier", 2.0f), danger("person", 0.9f)),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals(0.9f, signal.meters!!, 0.001f)
        assertEquals("person", signal.label)
    }

    @Test
    fun `hors couloir = silence`() {
        val signal = HazardSignal.of(
            listOf(reading("barrier", 0.8f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertFalse(signal.detected)
    }

    @Test
    fun `au-dela de trois metres = silence`() {
        val signal = HazardSignal.of(listOf(danger("barrier", 3.5f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `sans mesure = silence`() {
        val signal = HazardSignal.of(listOf(danger("barrier", Float.NaN)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `personne a 3m5 = detectee`() {
        val signal = HazardSignal.of(listOf(danger("person", 3.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(3.5f, signal.meters!!, 0.001f)
        assertEquals("person", signal.label)
    }

    @Test
    fun `personne a 4m5 = silence`() {
        val signal = HazardSignal.of(listOf(danger("person", 4.5f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `personne annoncee en 2 frames, doux`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "person")

        assertNull(watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la personne, passez à droite.", cue!!.phrase)
        assertFalse(cue.urgent)
    }

    @Test
    fun `personne imminente = mot seul immediat, urgent, sans securite`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "personne")

        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.PERSON, cue.kind)
        assertNull(cue.safetyPhrase)
    }

    @Test
    fun `mot imminent = cote oppose a la personne, FR EN`() {
        val first = HazardWatcher()
        val second = HazardWatcher()
        val third = HazardWatcher()
        val fourth = HazardWatcher()
        val atLeft = HazardSignal(detected = true, meters = 1.5f, label = "person", centerX = 0.4f)
        val atRight = HazardSignal(detected = true, meters = 1.5f, label = "person", centerX = 0.8f)
        val unknown = HazardSignal(detected = true, meters = 1.5f, label = "person")
        val centered = HazardSignal(detected = true, meters = 1.5f, label = "person", centerX = 0.5f)

        assertEquals("Droite.", first.onSignal(atLeft, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Gauche.", second.onSignal(atRight, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Right.", third.onSignal(unknown, 1_000L, SpeechLanguage.En)!!.phrase)
        assertEquals("Droite.", fourth.onSignal(centered, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals(SteeringCap.Right, HazardText.avoidSide(null))
        assertEquals(SteeringCap.Right, HazardText.avoidSide(0.4f))
        assertEquals(SteeringCap.Right, HazardText.avoidSide(0.5f))
        assertEquals(SteeringCap.Left, HazardText.avoidSide(0.8f))
    }

    @Test
    fun `mot imminent repete a 300 ms, immediat au changement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "person")

        assertEquals("Droite.", watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(signal, 1_299L, SpeechLanguage.Fr))
        assertEquals("Droite.", watcher.onSignal(signal, 1_300L, SpeechLanguage.Fr)!!.phrase)
        val moved = HazardSignal(detected = true, meters = 1.5f, label = "person", centerX = 0.8f)
        assertEquals("Gauche.", watcher.onSignal(moved, 1_350L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `doux puis imminent = le mot parle malgre le verrou`() {
        val watcher = HazardWatcher()
        val soft = HazardSignal(detected = true, meters = 3.5f, label = "person")

        watcher.onSignal(soft, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(soft, 2_000L, SpeechLanguage.Fr)
        val imminent = HazardSignal(detected = true, meters = 1.5f, label = "person")
        val cue = watcher.onSignal(imminent, 3_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.PERSON, cue.kind)
    }

    @Test
    fun `personne deja dite = silence jusqu'au rearmement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "person")

        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)
        assertNull(watcher.onSignal(signal, 13_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(signal, 14_000L, SpeechLanguage.Fr))
        repeat(3) { watcher.onSignal(HazardSignal.none(), 15_000L + it * 1_000L, SpeechLanguage.Fr) }
        assertNull(watcher.onSignal(signal, 18_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 19_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la personne, passez à droite.", cue!!.phrase)
    }

    @Test
    fun `personne dit comment l'eviter - cote oppose, FR EN`() {
        val person = HazardConcepts.nameOf("personne")!!
        assertEquals(
            "Attention à la personne, à 11 heures, passez à droite.",
            HazardText.personPhrase(person, SpeechLanguage.Fr, 0.4f),
        )
        assertEquals(
            "Attention à la personne, à 2 heures, passez à gauche.",
            HazardText.personPhrase(person, SpeechLanguage.Fr, 0.8f),
        )
        assertEquals(
            "Attention, person, at 2 o'clock, pass on the left.",
            HazardText.personPhrase(person, SpeechLanguage.En, 0.8f),
        )
        assertEquals(
            "Attention à la personne, passez à droite.",
            HazardText.personPhrase(person, SpeechLanguage.Fr),
        )
        assertEquals(
            "Attention à la personne, à midi, passez à droite.",
            HazardText.personPhrase(person, SpeechLanguage.Fr, 0.5f),
        )

        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "person", centerX = 0.8f)
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la personne, à 2 heures, passez à gauche.", cue!!.phrase)
    }

    @Test
    fun `deux positifs parlent, un isole non`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        assertNull(watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
        assertEquals(0.8f, cue.meters!!, 0.001f)
    }

    @Test
    fun `phrases FR EN`() {
        val barrier = HazardConcepts.nameOf("barrier")!!
        val person = HazardConcepts.nameOf("personne")!!
        assertEquals("Attention à la barrière.", HazardText.phrase(barrier, SpeechLanguage.Fr))
        assertEquals("Attention à la personne.", HazardText.phrase(person, SpeechLanguage.Fr))
        assertEquals("Attention, barrier.", HazardText.phrase(barrier, SpeechLanguage.En))
    }

    @Test
    fun `cooldown tait la repetition`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "barrier")
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        watcher.onSignal(signal, 3_000L, SpeechLanguage.Fr)
        assertNull(watcher.onSignal(signal, 4_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 13_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
    }

    @Test
    fun `personne preferee a la barriere meme plus proche, barriere en repli`() {
        val signal = HazardSignal.of(
            listOf(danger("barrier", 0.8f), danger("person", 3.5f)),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals("person", signal.label)
        assertEquals(3.5f, signal.meters!!, 0.001f)
        assertEquals("barrier", signal.alt!!.label)
        assertEquals(0.8f, signal.alt!!.meters!!, 0.001f)
    }

    @Test
    fun `personne imminente au bord du cadre = detectee plein axe`() {
        val signal = HazardSignal.of(
            listOf(reading("person", 1.5f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals("person", signal.label)
    }

    @Test
    fun `personne lointaine au bord du cadre = silence`() {
        val signal = HazardSignal.of(
            listOf(reading("person", 3.5f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertFalse(signal.detected)
    }

    @Test
    fun `barriere parle en repli quand la personne est deja dite`() {
        val watcher = HazardWatcher()
        val both = HazardSignal.of(
            listOf(danger("person", 3.5f), danger("barrier", 0.8f)),
            512, 1138,
        )
        watcher.onSignal(both, 1_000L, SpeechLanguage.Fr)
        val person = watcher.onSignal(both, 2_000L, SpeechLanguage.Fr)
        assertEquals(HazardKind.PERSON, person!!.kind)

        for (second in 3L..11L) watcher.onSignal(both, second * 1_000L, SpeechLanguage.Fr)
        val barrier = watcher.onSignal(both, 12_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière, à midi.", barrier!!.phrase)
        assertEquals(HazardKind.BARRIER, barrier.kind)
    }

    @Test
    fun `confirmation par sorte - alternance ne parle pas`() {
        val watcher = HazardWatcher()
        val person = HazardSignal(detected = true, meters = 3.5f, label = "person")
        val barrier = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        assertNull(watcher.onSignal(person, 1_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(barrier, 2_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(person, 3_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(barrier, 4_000L, SpeechLanguage.Fr))
        val confirmed = watcher.onSignal(barrier, 5_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", confirmed!!.phrase)
    }

    @Test
    fun `personne imminente ne consomme pas le cooldown doux`() {
        val watcher = HazardWatcher()
        val imminent = HazardSignal(detected = true, meters = 1.5f, label = "person")
        val barrier = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        assertEquals("Droite.", watcher.onSignal(imminent, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(barrier, 2_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(barrier, 3_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
    }

    @Test
    fun `poteau = silence`() {
        val signal = HazardSignal.of(listOf(danger("pole", 1.5f)), 512, 1138)

        assertFalse(signal.detected)

        val watcher = HazardWatcher()
        val manual = HazardSignal(detected = true, meters = 1.5f, label = "poteau")

        assertNull(watcher.onSignal(manual, 1_000L, SpeechLanguage.Fr))
    }

    @Test
    fun `sorte renseignee - divers sans alerte`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "borne")

        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals(HazardKind.OTHER, cue!!.kind)
        assertNull(cue.safetyPhrase)
    }
}
