package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mobilier proche dans le couloir : détecté, le plus proche nommé, le reste muet. */
class HazardWatchTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Mobilier centré (frame 512×1138). */
    private fun furniture(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    @Test
    fun `barriere proche dans le couloir = detectee et nommee`() {
        val signal = HazardSignal.of(listOf(furniture("barrier", 0.8f)), 512, 1138)

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
            listOf(furniture("barrier", 2.0f), furniture("pole", 0.9f)),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals(0.9f, signal.meters!!, 0.001f)
        assertEquals("pole", signal.label)
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
        val signal = HazardSignal.of(listOf(furniture("barrier", 3.5f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `sans mesure = silence`() {
        val signal = HazardSignal.of(listOf(furniture("barrier", Float.NaN)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `poteau a 3m5 = detecte`() {
        val signal = HazardSignal.of(listOf(furniture("pole", 3.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(3.5f, signal.meters!!, 0.001f)
        assertEquals("pole", signal.label)
    }

    @Test
    fun `poteau a 4m5 = silence`() {
        val signal = HazardSignal.of(listOf(furniture("pole", 4.5f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `poteau annonce en 2 frames, doux`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "pole")

        assertNull(watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention au poteau, passez à droite.", cue!!.phrase)
        assertFalse(cue.urgent)
    }

    @Test
    fun `poteau imminent = annonce immediate urgente`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "poteau")

        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Attention au poteau, passez à droite.", cue!!.phrase)
        assertTrue(cue.urgent)
    }

    @Test
    fun `poteau deja dit = silence jusqu'au rearmement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "pole")

        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)
        assertNull(watcher.onSignal(signal, 13_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(signal, 14_000L, SpeechLanguage.Fr))
        repeat(3) { watcher.onSignal(HazardSignal.none(), 15_000L + it * 1_000L, SpeechLanguage.Fr) }
        assertNull(watcher.onSignal(signal, 18_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 19_000L, SpeechLanguage.Fr)

        assertEquals("Attention au poteau, passez à droite.", cue!!.phrase)
    }

    @Test
    fun `poteau dit comment l'eviter - cote oppose, FR EN`() {
        val pole = HazardConcepts.nameOf("poteau")!!
        assertEquals(
            "Attention au poteau, à 11 heures, passez à droite.",
            HazardText.polePhrase(pole, SpeechLanguage.Fr, 0.4f),
        )
        assertEquals(
            "Attention au poteau, à 2 heures, passez à gauche.",
            HazardText.polePhrase(pole, SpeechLanguage.Fr, 0.8f),
        )
        assertEquals(
            "Attention, pole, at 2 o'clock, pass on the left.",
            HazardText.polePhrase(pole, SpeechLanguage.En, 0.8f),
        )
        assertEquals(
            "Attention au poteau, passez à droite.",
            HazardText.polePhrase(pole, SpeechLanguage.Fr),
        )

        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3.5f, label = "pole", centerX = 0.8f)
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention au poteau, à 2 heures, passez à gauche.", cue!!.phrase)
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
        val pole = HazardConcepts.nameOf("poteau")!!
        assertEquals("Attention à la barrière.", HazardText.phrase(barrier, SpeechLanguage.Fr))
        assertEquals("Attention au poteau.", HazardText.phrase(pole, SpeechLanguage.Fr))
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
    fun `poteau prefere a la barriere meme plus proche, barriere en repli`() {
        val signal = HazardSignal.of(
            listOf(furniture("barrier", 0.8f), furniture("pole", 3.5f)),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals("pole", signal.label)
        assertEquals(3.5f, signal.meters!!, 0.001f)
        assertEquals("barrier", signal.alt!!.label)
        assertEquals(0.8f, signal.alt!!.meters!!, 0.001f)
    }

    @Test
    fun `poteau imminent au bord du cadre = detecte plein axe`() {
        val signal = HazardSignal.of(
            listOf(reading("pole", 1.5f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals("pole", signal.label)
    }

    @Test
    fun `poteau lointain au bord du cadre = silence`() {
        val signal = HazardSignal.of(
            listOf(reading("pole", 3.5f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertFalse(signal.detected)
    }

    @Test
    fun `barriere parle en repli quand le poteau est deja dit`() {
        val watcher = HazardWatcher()
        val both = HazardSignal.of(
            listOf(furniture("pole", 3.5f), furniture("barrier", 0.8f)),
            512, 1138,
        )
        watcher.onSignal(both, 1_000L, SpeechLanguage.Fr)
        val pole = watcher.onSignal(both, 2_000L, SpeechLanguage.Fr)
        assertEquals(HazardKind.POLE, pole!!.kind)

        for (second in 3L..11L) watcher.onSignal(both, second * 1_000L, SpeechLanguage.Fr)
        val barrier = watcher.onSignal(both, 12_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière, à midi.", barrier!!.phrase)
        assertEquals(HazardKind.BARRIER, barrier.kind)
    }

    @Test
    fun `confirmation par sorte - alternance ne parle pas`() {
        val watcher = HazardWatcher()
        val pole = HazardSignal(detected = true, meters = 3.5f, label = "pole")
        val barrier = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        assertNull(watcher.onSignal(pole, 1_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(barrier, 2_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(pole, 3_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(barrier, 4_000L, SpeechLanguage.Fr))
        val confirmed = watcher.onSignal(barrier, 5_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", confirmed!!.phrase)
    }

    @Test
    fun `poteau imminent = alerte securite avec evitement, FR EN`() {
        assertEquals(
            "Poteau, passez à droite.",
            HazardText.safetyPolePhrase(SpeechLanguage.Fr, null),
        )
        assertEquals(
            "Poteau, passez à gauche.",
            HazardText.safetyPolePhrase(SpeechLanguage.Fr, 0.8f),
        )
        assertEquals(
            "Pole, pass on the right.",
            HazardText.safetyPolePhrase(SpeechLanguage.En, 0.4f),
        )

        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "poteau", centerX = 0.8f)
        val cue = watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)

        assertEquals("Poteau, passez à gauche.", cue!!.safetyPhrase)
        assertEquals(HazardKind.POLE, cue.kind)
        assertTrue(cue.urgent)
    }

    @Test
    fun `poteau imminent ne consomme pas le cooldown doux`() {
        val watcher = HazardWatcher()
        val imminent = HazardSignal(detected = true, meters = 1.5f, label = "pole")
        val barrier = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        watcher.onSignal(imminent, 1_000L, SpeechLanguage.Fr)
        assertNull(watcher.onSignal(barrier, 2_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(barrier, 3_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
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
