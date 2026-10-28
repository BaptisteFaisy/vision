package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurbWatcherTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Trottoir proche, couloir central, bande basse (frame 512×1138). */
    private fun curb(meters: Float = 0.8f) =
        reading("sidewalk", meters, listOf(156f, 700f, 356f, 1100f))

    @Test
    fun `concepts trottoir reconnus, marche et voiture non`() {
        assertTrue(CurbConcepts.isCurb("curb"))
        assertTrue(CurbConcepts.isCurb("sidewalk"))
        assertTrue(CurbConcepts.isCurb("Trottoir"))
        assertTrue(CurbConcepts.isCurb("bordures"))
        assertTrue(CurbConcepts.isCurb("step"))

        assertFalse(CurbConcepts.isCurb("marche"))
        assertFalse(CurbConcepts.isCurb("car"))
        assertFalse(CurbConcepts.isCurb("person"))
        assertFalse(CurbConcepts.isCurb("  "))
    }

    @Test
    fun `trottoir proche en bas du couloir = detecte`() {
        val signal = CurbSignal.of(listOf(curb(0.8f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.8f, signal.meters!!, 0.001f)
    }

    @Test
    fun `trottoir au-dela d un metre = silence`() {
        val signal = CurbSignal.of(listOf(curb(1.5f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `trottoir hors couloir ou hors bande basse = silence`() {
        val side = reading("sidewalk", 0.8f, listOf(10f, 700f, 100f, 1100f))
        val high = reading("sidewalk", 0.8f, listOf(156f, 100f, 356f, 500f))

        assertFalse(CurbSignal.of(listOf(side), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(high), 512, 1138).detected)
    }

    @Test
    fun `autre label ou geometrie illisible = silence`() {
        val car = reading("car", 0.8f, listOf(156f, 700f, 356f, 1100f))
        val broken = reading("sidewalk", 0.8f, listOf(1f, 2f))

        assertFalse(CurbSignal.of(listOf(car), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(broken), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(curb()), 0, 1138).detected)
    }

    @Test
    fun `le plus proche gagne`() {
        val far = reading("sidewalk", 1f, listOf(156f, 700f, 356f, 1100f))
        val near = reading("curb", 0.6f, listOf(206f, 800f, 306f, 1100f))

        val signal = CurbSignal.of(listOf(far, near), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.6f, signal.meters!!, 0.001f)
    }

    @Test
    fun `phrases FR et EN`() {
        assertEquals("Trottoir, levez la jambe.", CurbText.phrase(SpeechLanguage.Fr))
        assertEquals("Curb, lift your foot.", CurbText.phrase(SpeechLanguage.En))
    }

    @Test
    fun `trois positifs consecutifs = annonce`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        assertNull(watcher.onSignal(positive, 1000L))
        assertNull(watcher.onSignal(positive, 2000L))
        val cue = watcher.onSignal(positive, 3000L)

        assertNotNull(cue)
        assertEquals("Trottoir, levez la jambe.", cue!!.phrase)
        assertEquals(0.8f, cue.meters!!, 0.001f)
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(positive, 2000L)
        watcher.onSignal(CurbSignal.none(), 3000L)
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNotNull(watcher.onSignal(positive, 6000L))
    }

    @Test
    fun `cooldown dix secondes sans repetition`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(positive, 2000L)
        assertNotNull(watcher.onSignal(positive, 3000L))
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNull(watcher.onSignal(positive, 6000L))
        assertNull(watcher.onSignal(positive, 12_000L))
        // cooldown écoulé (10 s) + signal confirmé : reparle aussitôt
        assertNotNull(watcher.onSignal(positive, 13_000L))
        // … puis nouveau silence (compte reparti de zéro)
        assertNull(watcher.onSignal(positive, 14_000L))
        assertNull(watcher.onSignal(positive, 15_000L))
        assertNull(watcher.onSignal(positive, 16_000L))
    }

    @Test
    fun `reset re-arme l annonce`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(positive, 2000L)
        assertNotNull(watcher.onSignal(positive, 3000L))
        watcher.reset()
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNotNull(watcher.onSignal(positive, 6000L))
    }
}
