package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mot d'évitement continu (gens, poteaux) + douce confirmée (reste) ; le reste muet. */
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
    fun `au-dela de six metres = silence`() {
        val signal = HazardSignal.of(listOf(danger("barrier", 6.5f)), 512, 1138)

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
    fun `personne a 4m5 droit devant = detectee, palier loin`() {
        val signal = HazardSignal.of(listOf(danger("person", 4.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(4.5f, signal.meters!!, 0.001f)
        assertEquals("person", signal.label)
    }

    @Test
    fun `personne a 15 m droit devant = detectee`() {
        val signal = HazardSignal.of(listOf(danger("person", 15f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(15f, signal.meters!!, 0.001f)
        assertEquals("person", signal.label)
    }

    @Test
    fun `personne a 16 m = silence`() {
        val signal = HazardSignal.of(listOf(danger("person", 16f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `personne loin hors couloir = silence`() {
        val signal = HazardSignal.of(
            listOf(reading("person", 10f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertFalse(signal.detected)
    }

    @Test
    fun `personne = mot continu des la 1re frame, urgent, sans securite`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "person")

        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.PERSON, cue.kind)
        assertNull(cue.safetyPhrase)
    }

    @Test
    fun `personne imminente = mot continu des la 1re frame`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "personne")

        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.PERSON, cue.kind)
        assertNull(cue.safetyPhrase)
    }

    @Test
    fun `mot continu = cote oppose, FR EN`() {
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
    fun `mot continu de personne repete a 300 ms, immediat au changement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "person")

        assertEquals("Droite.", watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(signal, 1_299L, SpeechLanguage.Fr))
        assertEquals("Droite.", watcher.onSignal(signal, 1_300L, SpeechLanguage.Fr)!!.phrase)
        val moved = HazardSignal(detected = true, meters = 1.5f, label = "person", centerX = 0.8f)
        assertEquals("Gauche.", watcher.onSignal(moved, 1_350L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `personne presente = mot repete sans cooldown ni rearmement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "person")

        assertEquals("Droite.", watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Droite.", watcher.onSignal(signal, 1_300L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Droite.", watcher.onSignal(signal, 11_300L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Droite.", watcher.onSignal(signal, 11_600L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `mot d'evitement - cote oppose, FR EN, gens et poteaux`() {
        assertEquals(
            "Droite.",
            HazardText.avoidWord(SpeechLanguage.Fr, 0.4f),
        )
        assertEquals(
            "Gauche.",
            HazardText.avoidWord(SpeechLanguage.Fr, 0.8f),
        )
        assertEquals(
            "Left.",
            HazardText.avoidWord(SpeechLanguage.En, 0.8f),
        )
        assertEquals(
            "Right.",
            HazardText.avoidWord(SpeechLanguage.En, 0.4f),
        )
        assertEquals(
            "Droite.",
            HazardText.avoidWord(SpeechLanguage.Fr),
        )
        assertEquals(
            "Droite.",
            HazardText.avoidWord(SpeechLanguage.Fr, 0.5f),
        )

        val watcher = HazardWatcher()
        val person = HazardSignal(detected = true, meters = 3.5f, label = "person", centerX = 0.8f)
        assertEquals("Gauche.", watcher.onSignal(person, 1_000L, SpeechLanguage.Fr)!!.phrase)
        val pole = HazardSignal(detected = true, meters = 9f, label = "poteau", centerX = 0.4f)
        val cue = watcher.onSignal(pole, 1_300L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.POLE, cue.kind)
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
    fun `personne preferee a la barriere meme plus proche`() {
        val signal = HazardSignal.of(
            listOf(danger("barrier", 0.8f), danger("person", 3.5f)),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals("person", signal.label)
        assertEquals(3.5f, signal.meters!!, 0.001f)
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
    fun `barriere tait tant que la personne parle, parle a son depart`() {
        val watcher = HazardWatcher()
        val both = HazardSignal.of(
            listOf(danger("person", 3.5f), danger("barrier", 0.8f)),
            512, 1138,
        )

        for (second in 0L..11L) {
            val cue = watcher.onSignal(both, 1_000L + second * 1_000L, SpeechLanguage.Fr)
            assertEquals("Droite.", cue!!.phrase)
            assertEquals(HazardKind.PERSON, cue.kind)
        }

        val alone = HazardSignal.of(listOf(danger("barrier", 0.8f)), 512, 1138)
        assertNull(watcher.onSignal(alone, 13_000L, SpeechLanguage.Fr))
        val barrier = watcher.onSignal(alone, 14_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière, à midi.", barrier!!.phrase)
        assertEquals(HazardKind.BARRIER, barrier.kind)
    }

    @Test
    fun `douce confirmee en 2 frames meme en alternance`() {
        val watcher = HazardWatcher()
        val barrier = HazardSignal(detected = true, meters = 0.8f, label = "barrier")
        val cat = HazardSignal(detected = true, meters = 1.2f, label = "chat")

        assertNull(watcher.onSignal(barrier, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(cat, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention au chat.", cue!!.phrase)
    }

    @Test
    fun `mot continu ne consomme pas le cooldown doux`() {
        val watcher = HazardWatcher()
        val person = HazardSignal(detected = true, meters = 12f, label = "person")
        val barrier = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        assertEquals("Droite.", watcher.onSignal(person, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(barrier, 2_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(barrier, 3_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
    }

    @Test
    fun `poteau proche = mot d'evitement des la 1re frame, continu`() {
        val signal = HazardSignal.of(listOf(danger("pole", 1.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals("pole", signal.label)

        val watcher = HazardWatcher()
        val manual = HazardSignal(detected = true, meters = 1.5f, label = "poteau", centerX = 0.5f)

        val cue = watcher.onSignal(manual, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.POLE, cue.kind)
        assertNull(cue.safetyPhrase)
        assertEquals(7, cue.level)
        assertEquals("pole-avoid", cue.antiRepeatKey)
    }

    @Test
    fun `poteau a 15 m droit devant = detecte`() {
        val signal = HazardSignal.of(listOf(danger("poteau", 15f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(15f, signal.meters!!, 0.001f)
        assertEquals("poteau", signal.label)
    }

    @Test
    fun `poteau a 16 m = silence`() {
        val signal = HazardSignal.of(listOf(danger("poteau", 16f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `poteau loin hors couloir = silence`() {
        val signal = HazardSignal.of(
            listOf(reading("poteau", 10f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertFalse(signal.detected)
    }

    @Test
    fun `poteau lointain = mot continu, urgent`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 12f, label = "poteau", centerX = 0.5f)

        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.POLE, cue.kind)
    }

    @Test
    fun `mot continu de poteau repete a 300 ms, immediat au changement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 9f, label = "poteau")

        assertEquals("Droite.", watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(signal, 1_299L, SpeechLanguage.Fr))
        assertEquals("Droite.", watcher.onSignal(signal, 1_300L, SpeechLanguage.Fr)!!.phrase)
        val moved = HazardSignal(detected = true, meters = 9f, label = "poteau", centerX = 0.8f)
        assertEquals("Gauche.", watcher.onSignal(moved, 1_350L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `personne lointaine = mot continu des la 1re frame`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 12f, label = "person", centerX = 0.8f)

        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Gauche.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.PERSON, cue.kind)
        assertEquals(2, cue.level)
        assertEquals("human-imminent", cue.antiRepeatKey)
    }

    @Test
    fun `sorte renseignee - divers sans alerte`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "poubelle")

        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals(HazardKind.OTHER, cue!!.kind)
        assertNull(cue.safetyPhrase)
    }
}
