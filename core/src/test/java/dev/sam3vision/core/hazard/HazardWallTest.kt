package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.AvoidOrder
import dev.sam3vision.core.live.ClearView
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Murs : jamais nommés, mot d'évitement continu comme les poteaux (ordre user du 02/11). */
class HazardWallTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Danger centré (frame 512×1138). */
    private fun danger(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    @Test
    fun `mur proche = mot d'evitement des la 1re frame, jamais nomme`() {
        val signal = HazardSignal.of(listOf(danger("wall", 1.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals("wall", signal.label)

        val watcher = HazardWatcher()
        val manual = HazardSignal(detected = true, meters = 1.5f, label = "mur", centerX = 0.5f)

        val cue = watcher.onSignal(manual, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.OTHER, cue.kind)
        assertNull(cue.safetyPhrase)
        assertEquals(5, cue.level)
        assertEquals("wall-avoid", cue.antiRepeatKey)
    }

    @Test
    fun `mur lateral = cote oppose, FR EN`() {
        val watcher = HazardWatcher()
        val right = HazardSignal(detected = true, meters = 1.5f, label = "wall", centerX = 0.8f)
        val left = HazardSignal(detected = true, meters = 1.5f, label = "wall", centerX = 0.2f)

        assertEquals("Gauche.", watcher.onSignal(right, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Right.", HazardWatcher().onSignal(left, 1_000L, SpeechLanguage.En)!!.phrase)
    }

    @Test
    fun `mot continu de mur repete a 300 ms, immediat au changement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "mur")

        assertEquals("Droite.", watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(signal, 1_299L, SpeechLanguage.Fr))
        assertEquals("Droite.", watcher.onSignal(signal, 1_300L, SpeechLanguage.Fr)!!.phrase)
        val moved = HazardSignal(detected = true, meters = 1.5f, label = "mur", centerX = 0.8f)
        assertEquals("Gauche.", watcher.onSignal(moved, 1_350L, SpeechLanguage.Fr)!!.phrase)
    }
}

/** Le guetteur suit le chemin : le côté vient de la scène, plus du centre seul. */
class HazardSteeredTest {

    private val pole = PlacedLabel("pole", 0.7f, 0.5f, 1.5f, 0.68f, 0.72f)

    private fun view(side: GuidanceSide, threat: PlacedLabel = pole) =
        ClearView(AvoidOrder(side, threat), side)

    @Test
    fun `ordre fait parler un obstacle hors de la bande centrale`() {
        val signal = HazardSignal.none().steeredBy(view(GuidanceSide.Left))

        assertTrue(signal.detected)
        assertEquals("pole", signal.label)
        assertEquals(1.5f, signal.meters!!, 0.001f)
        assertEquals(GuidanceSide.Left, signal.side)
    }

    @Test
    fun `sans ordre le signal vide reste vide`() {
        assertFalse(HazardSignal.none().steeredBy(ClearView(null, GuidanceSide.Left)).detected)
        assertFalse(HazardSignal.none().steeredBy(null).detected)
    }

    @Test
    fun `corps retenu garde son label, le cote vient de la scene`() {
        val body = HazardSignal(detected = true, meters = 14f, label = "person", centerX = 0.45f)

        val signal = body.steeredBy(view(GuidanceSide.Left))

        assertEquals("person", signal.label)
        assertEquals(GuidanceSide.Left, signal.side)
    }

    @Test
    fun `le mot suit le cote du chemin, pas l opposé du centre`() {
        val body = HazardSignal(detected = true, meters = 3f, label = "pole", centerX = 0.45f)

        val cue = HazardWatcher().onSignal(body.copy(side = GuidanceSide.Left), 0L, SpeechLanguage.En)

        assertEquals("Left.", cue?.phrase)
    }

    @Test
    fun `sans chemin le mot reste l opposé du centre`() {
        val body = HazardSignal(detected = true, meters = 3f, label = "pole", centerX = 0.45f)

        assertEquals("Right.", HazardWatcher().onSignal(body, 0L, SpeechLanguage.En)?.phrase)
    }

    @Test
    fun `plein axe a droite, un pour cent du cadre ne change pas le cote`() {
        assertEquals(SteeringCap.Right, HazardText.avoidSide(0.50f))
        assertEquals(SteeringCap.Right, HazardText.avoidSide(0.51f))
        assertEquals(SteeringCap.Right, HazardText.avoidSide(0.54f))
        assertEquals(SteeringCap.Left, HazardText.avoidSide(0.60f))
    }

    @Test
    fun `un mur ou un poteau a 9 m fait parler le guetteur, cote du juge`() {
        for (label in listOf("wall", "pole", "barrier")) {
            val obstacle = PlacedLabel(label, 0.5f, 0.5f, 9f, 0.47f, 0.53f)
            val view = ClearView(AvoidOrder(GuidanceSide.Left, obstacle), GuidanceSide.Left)
            val cue = HazardWatcher().onSignal(HazardSignal.none().steeredBy(view), 1_000L, SpeechLanguage.En)
            assertEquals(label, "Left.", cue?.phrase)
        }
    }
}
