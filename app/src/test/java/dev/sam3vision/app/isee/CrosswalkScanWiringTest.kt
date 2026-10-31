package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrosswalkScanPhase
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan traversée côté sonde : déclencheur (arrivé + passage à moins de 2 m)
 * et priorité de la phrase (feu > scan > véhicules > personnes). Le lacet
 * est `null` en JVM (sans `Context`) : le scan y est temporisé, les virages
 * sont couverts par `CrosswalkScanTest`.
 */
class CrosswalkScanWiringTest {

    private val width = 640
    private val height = 480
    private val lens = LensSpec.Default

    private fun crosswalk(box: List<Float>, score: Float = 0.9f): SegObject =
        SegObject("crosswalk", score, box, maskPngB64 = "")

    private fun reading(meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = "crosswalk", meters = meters, rawMeters = meters, box = box)

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = width, height = height)

    private fun carReading(meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = "car", meters = meters, rawMeters = meters, box = box)

    private fun carBox(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }

    /** Boîte qui touche le bas du cadre (480 − 2 px de tolérance) : l'arrivée géométrique. */
    private fun bottomBox(): List<Float> = listOf(100f, 300f, 540f, 479f)

    /** Boîte au milieu du champ : le passage est devant, pas sous les pieds. */
    private fun midBox(): List<Float> = listOf(200f, 100f, 440f, 200f)

    private fun trigger(objects: List<SegObject>, readings: List<DistanceReading>): Boolean =
        scanTriggerOf(objects, width, height, readings, pose = null, lens)

    @Test
    fun `declencheur - bas touche sans mesure, les deux conditions remplies`() {
        assertTrue(trigger(listOf(crosswalk(bottomBox())), emptyList()))
    }

    @Test
    fun `declencheur - bas touche et 1,5m`() {
        val box = bottomBox()
        assertTrue(trigger(listOf(crosswalk(box)), listOf(reading(1.5f, box))))
    }

    @Test
    fun `declencheur - bas touche mais 3m mesures, pas de scan`() {
        val box = bottomBox()
        assertFalse("mesuré ≥ 2 m : la 2e condition manque", trigger(listOf(crosswalk(box)), listOf(reading(3f, box))))
    }

    @Test
    fun `declencheur - 1,2m au milieu du champ, arrive par la profondeur`() {
        val box = midBox()
        assertTrue(trigger(listOf(crosswalk(box)), listOf(reading(1.2f, box))))
    }

    @Test
    fun `declencheur - 1,8m au milieu du champ, pas encore dessus`() {
        val box = midBox()
        assertFalse("moins de 2 m mais pas arrivé : la 1re condition manque", trigger(listOf(crosswalk(box)), listOf(reading(1.8f, box))))
    }

    @Test
    fun `declencheur - sans passage, score faible, dimensions folles`() {
        assertFalse(trigger(emptyList(), emptyList()))
        assertFalse(trigger(listOf(crosswalk(bottomBox(), score = 0.2f)), emptyList()))
        assertFalse(trigger(listOf(SegObject("car", 0.9f, bottomBox(), maskPngB64 = "")), emptyList()))
        assertFalse(scanTriggerOf(listOf(crosswalk(bottomBox())), 0, height, emptyList(), null, lens))
    }

    @Test
    fun `lacet JVM - null sans capteur, le scan reste temporise`() {
        CrosswalkScanWatch.reset()
        CrosswalkScanWatch.ensureMeter(null)
        assertNull(CrosswalkScanWatch.yawDeg(1_000L))
        assertNull(CrosswalkScanWatch.update(0L, trigger = true, movingNow = false, feu = false, SpeechLanguage.Fr))
        assertEquals("Stop.", CrosswalkScanWatch.update(100L, trigger = true, movingNow = false, feu = false, SpeechLanguage.Fr))
        assertEquals(CrosswalkScanPhase.Stop, CrosswalkScanWatch.phase())
        CrosswalkScanWatch.reset()
        assertEquals(CrosswalkScanPhase.Idle, CrosswalkScanWatch.phase())
    }

    @Test
    fun `priorite - le scan couvre les vehicules`() {
        resetWatches()
        val language = SpeechLanguage.Fr

        val first = crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(100f))), listOf(carReading(5f, carBox(100f))), 0L, language, scanTrigger = true)
        assertEquals("scan et véhicule en confirmation : le repli tout droit parle", "Tout droit.", first)
        val second = crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(110f))), listOf(carReading(5f, carBox(110f))), 100L, language, scanTrigger = true)
        assertEquals("Stop.", second)
        val third = crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(120f))), listOf(carReading(5f, carBox(120f))), 200L, language, scanTrigger = true)
        assertEquals("Stop. (le véhicule aurait parlé : brut vrai)", "Stop.", third)
        assertTrue(CrossingVehicleWatch.movingNow)
        val fourth = crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(130f))), listOf(carReading(5f, carBox(130f))), 1_000L, language, scanTrigger = true)
        assertEquals("Regardez à gauche.", fourth)
        resetWatches()
    }

    @Test
    fun `priorite - le feu couvre le scan`() {
        resetWatches()
        val language = SpeechLanguage.Fr
        val cue = CrossingCue("Rouge, attendez.", true, CrossingState.Red)

        crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, language, scanTrigger = true)
        assertEquals(
            "Rouge, attendez.",
            crossingPhraseOf(cue, emptyList(), emptyList(), emptyList(), 100L, language, scanTrigger = true),
        )
        assertEquals("le scan avance en silence", CrosswalkScanPhase.Stop, CrosswalkScanWatch.phase())
        resetWatches()
    }

    @Test
    fun `priorite - procedure scan, le guidage se tait devant le feu`() {
        resetWatches()
        val language = SpeechLanguage.Fr
        val cue = CrossingCue("Rouge, attendez.", true, CrossingState.Red)

        crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 0L, language, scanTrigger = true)
        crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), 100L, language, scanTrigger = true)
        assertTrue(scanRunning())
        assertEquals(
            "Rouge, attendez.",
            crossingPhraseOf(cue, emptyList(), emptyList(), emptyList(), 200L, language, scanTrigger = true, steering = "Gauche."),
        )
        resetWatches()
    }

    @Test
    fun `priorite - le stop vehicules passe devant le guidage`() {
        resetWatches()
        val language = SpeechLanguage.Fr

        assertEquals("Tout droit.", crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(100f))), listOf(carReading(5f, carBox(100f))), 0L, language))
        assertNull("même mot, battement pas écoulé", crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(110f))), listOf(carReading(5f, carBox(110f))), 100L, language))
        assertEquals(
            "Stop, voiture en mouvement.",
            crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(120f))), listOf(carReading(5f, carBox(120f))), 200L, language, steering = "Gauche."),
        )
        resetWatches()
    }

    @Test
    fun `sans declencheur - vehicules seuls, scan au repos`() {
        resetWatches()
        val language = SpeechLanguage.Fr

        assertEquals("Tout droit.", crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(100f))), listOf(carReading(5f, carBox(100f))), 0L, language))
        assertNull("même mot, battement pas écoulé", crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(110f))), listOf(carReading(5f, carBox(110f))), 100L, language))
        assertEquals(
            "mouvement proche (5 m ≤ 8 m) : alerte stop en Traversée (contrat § 22)",
            "Stop, voiture en mouvement.",
            crossingPhraseOf(null, emptyList(), listOf(det("car", carBox(120f))), listOf(carReading(5f, carBox(120f))), 200L, language),
        )
        assertEquals(CrosswalkScanPhase.Idle, CrosswalkScanWatch.phase())
        resetWatches()
    }

    private fun resetWatches() {
        CrosswalkScanWatch.reset()
        CrossingVehicleWatch.reset()
        CrossingPeopleWatch.reset()
    }
}
