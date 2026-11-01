package dev.sam3vision.core.seuils

import dev.sam3vision.core.crossing.CrossingApproachWatcher
import dev.sam3vision.core.crossing.CrossingArrivalWatcher
import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.crossing.CrossingLateralAnnouncer
import dev.sam3vision.core.curb.CurbEdge
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.curb.SidewalkWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.FenceOpeningConcepts
import dev.sam3vision.core.hazard.FenceOpeningSignal
import dev.sam3vision.core.hazard.FenceOpeningWatcher
import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairCountVoice
import dev.sam3vision.core.stairs.StairDirection
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairText
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.traverse.VehicleStateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seuils, phrases et positions dehors — SPEC v4 (mission seuils-dehors).
 *
 * Humains niveau 2 (< 10 m, phrase + cadran + distance, imminent ≤ 2 m =
 * mot immédiat), grillages/murs/barrières niveau 4 (< 6 m, 4 s), bordures
 * niveau 5 (< 6 m, 2 s), escaliers niveau 6 (jalons 4/2/1 + marches dites,
 * 2 s), poteaux/mobilier/divers niveau 7 (< 6 m, 2 s), passages niveau 8
 * (< 6 m, annonce 1 fois par vue + alignement au changement + arrivée
 * 1 fois), trottoirs niveau 10 (inchangés), textes EN voitures (présence
 * seule + pluriel). La détection voitures et la traversée sont d'autres
 * missions — ici seules les phrases voitures sont verrouillées.
 */
class SeuilsDehorsV4Test {

    private val En = SpeechLanguage.En

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Objet centré (frame 512×1138, cadran midi). */
    private fun centered(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    private fun softPerson(meters: Float) =
        HazardSignal(detected = true, meters = meters, label = "person", centerX = 0.5f)

    // Humains — niveau 2.

    @Test
    fun `humain a 9m dit phrase cadran distance niveau 2`() {
        val signal = HazardSignal.of(listOf(centered("person", 9f)), 512, 1138)
        assertTrue(signal.detected)
        val watcher = HazardWatcher()
        assertNull(watcher.onSignal(signal, 1_000L, En))
        val cue = watcher.onSignal(signal, 1_100L, En)

        assertEquals("Attention, person, at 12 o'clock, 9 meters away, pass on the right.", cue!!.phrase)
        assertEquals(2, cue.level)
        assertEquals("human-soft", cue.antiRepeatKey)
        assertFalse(cue.urgent)
    }

    @Test
    fun `humain a 10m parle, au dela silence`() {
        assertTrue(HazardSignal.of(listOf(centered("person", 10f)), 512, 1138).detected)
        assertFalse(HazardSignal.of(listOf(centered("person", 10.5f)), 512, 1138).detected)
    }

    @Test
    fun `humain imminent dit le mot immediat des la 1re frame`() {
        val watcher = HazardWatcher()
        val left = HazardSignal(detected = true, meters = 1.8f, label = "person", centerX = 0.8f)
        val cue = watcher.onSignal(left, 1_000L, En)

        assertEquals("Left.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(2, cue.level)
        assertEquals("human-imminent", cue.antiRepeatKey)
        assertNull(watcher.onSignal(left, 1_100L, En))
        assertNotNull(watcher.onSignal(left, 1_400L, En))
        val right = HazardSignal(detected = true, meters = 1.8f, label = "person", centerX = 0.2f)
        assertEquals("Right.", watcher.onSignal(right, 1_500L, En)!!.phrase)
    }

    @Test
    fun `personne douce anti repetition 2s apres rearmement`() {
        val watcher = HazardWatcher()
        val signal = softPerson(5f)
        watcher.onSignal(signal, 1_000L, En)
        assertNotNull(watcher.onSignal(signal, 1_100L, En))
        repeat(3) { watcher.onSignal(HazardSignal.none(), 1_200L + it * 100L, En) }
        assertNull(watcher.onSignal(signal, 1_500L, En))
        assertNull(watcher.onSignal(signal, 1_600L, En))
        assertNotNull(watcher.onSignal(signal, 3_200L, En))
    }

    // Grillages, murs, barrières — niveau 4.

    @Test
    fun `cloture mur barriere parlent niveau 4 sous 6m`() {
        for (label in listOf("fence", "grillage", "wall", "mur", "barrier")) {
            val watcher = HazardWatcher()
            val signal = HazardSignal(detected = true, meters = 5.5f, label = label, centerX = 0.5f)
            watcher.onSignal(signal, 1_000L, En)
            val cue = watcher.onSignal(signal, 1_100L, En)
            assertNotNull("label $label", cue)
            assertEquals("label $label", 4, cue!!.level)
            assertEquals("label $label", "fence", cue.antiRepeatKey)
        }
        val watcher = HazardWatcher()
        val barrier = HazardSignal(detected = true, meters = 5.5f, label = "barrier", centerX = 0.5f)
        watcher.onSignal(barrier, 1_000L, En)
        assertEquals("Attention, barrier, at 12 o'clock.", watcher.onSignal(barrier, 1_100L, En)!!.phrase)
    }

    @Test
    fun `mobilier au dela de 6m silence, a 6m parle`() {
        assertFalse(HazardSignal.of(listOf(centered("barrier", 6.5f)), 512, 1138).detected)
        assertFalse(HazardSignal.of(listOf(centered("pole", 6.1f)), 512, 1138).detected)
        assertTrue(HazardSignal.of(listOf(centered("barrier", 6f)), 512, 1138).detected)
    }

    @Test
    fun `grillage anti repetition 4s`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 3f, label = "fence", centerX = 0.5f)
        watcher.onSignal(signal, 1_000L, En)
        assertNotNull(watcher.onSignal(signal, 1_100L, En))
        watcher.onSignal(signal, 4_000L, En)
        assertNull(watcher.onSignal(signal, 4_100L, En))
        assertNotNull(watcher.onSignal(signal, 5_200L, En))
    }

    // Poteaux, mobilier vertical, divers — niveau 7.

    @Test
    fun `poteau parle depuis v4 niveau 7`() {
        assertTrue(HazardConcepts.isSpoken("pole"))
        assertTrue(HazardConcepts.isSpoken("poteau"))
        assertTrue(HazardConcepts.isPole("poteau"))
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 5f, label = "pole", centerX = 0.5f)
        watcher.onSignal(signal, 1_000L, En)
        val cue = watcher.onSignal(signal, 1_100L, En)

        assertEquals("Attention, pole, at 12 o'clock.", cue!!.phrase)
        assertEquals(7, cue.level)
        assertEquals("pole", cue.antiRepeatKey)
    }

    @Test
    fun `lampadaire panneau animal poubelle souvenirs niveau 7`() {
        for (label in listOf("street lamp", "sign", "animal", "chat", "trash can", "poubelle", "souvenir shop", "beanie", "socks")) {
            assertTrue("parlé $label", HazardConcepts.isSpoken(label))
            val watcher = HazardWatcher()
            val signal = HazardSignal(detected = true, meters = 4f, label = label, centerX = 0.5f)
            watcher.onSignal(signal, 1_000L, En)
            val cue = watcher.onSignal(signal, 1_100L, En)
            assertNotNull("label $label", cue)
            assertEquals("label $label", 7, cue!!.level)
            assertEquals("label $label", "pole", cue.antiRepeatKey)
        }
    }

    @Test
    fun `borne et portail supprimes ne parlent plus`() {
        for (label in listOf("borne", "bollard", "plot", "potelet", "gate", "portail")) {
            assertFalse("muet $label", HazardConcepts.isSpoken(label))
        }
        assertFalse(HazardSignal.of(listOf(centered("borne", 2f)), 512, 1138).detected)
        assertFalse(HazardSignal.of(listOf(centered("portail", 2f)), 512, 1138).detected)
        assertFalse(FenceOpeningConcepts.isFence("portail"))
        assertFalse(FenceOpeningConcepts.isGate("portail"))
        assertTrue(FenceOpeningConcepts.isFence("fence"))
    }

    @Test
    fun `routage niveaux et cles par label`() {
        assertEquals(2, HazardConcepts.levelOf("person"))
        assertEquals(2, HazardConcepts.levelOf("child"))
        assertEquals(2, HazardConcepts.levelOf("dog"))
        assertEquals(2, HazardConcepts.levelOf("stroller"))
        assertEquals(4, HazardConcepts.levelOf("fence"))
        assertEquals(4, HazardConcepts.levelOf("wall"))
        assertEquals(4, HazardConcepts.levelOf("mur"))
        assertEquals(4, HazardConcepts.levelOf("barrier"))
        assertEquals(7, HazardConcepts.levelOf("pole"))
        assertEquals(7, HazardConcepts.levelOf("sign"))
        assertEquals(7, HazardConcepts.levelOf("street lamp"))
        assertEquals(7, HazardConcepts.levelOf("scooter"))
        assertEquals(7, HazardConcepts.levelOf("animal"))
        assertEquals(7, HazardConcepts.levelOf("trash can"))
        assertEquals(7, HazardConcepts.levelOf("souvenir shop"))
        assertEquals(7, HazardConcepts.levelOf("trampoline"))
        assertEquals("human-soft", HazardConcepts.antiRepeatKey("person"))
        assertEquals("fence", HazardConcepts.antiRepeatKey("barrier"))
        assertEquals("pole", HazardConcepts.antiRepeatKey("pole"))
    }

    // Bordures — niveau 5.

    @Test
    fun `bordure detectee sous 6m niveau 5`() {
        val signal = CurbSignal.of(listOf(centered("curb", 5.5f)), 512, 1138)
        assertTrue(signal.detected)
        val watcher = CurbWatcher()
        assertNull(watcher.onSignal(signal, 1_000L, En))
        val cue = watcher.onSignal(signal, 1_100L, En)

        assertEquals("Curb detected, at 12 o'clock.", cue!!.phrase)
        assertEquals(5, cue.level)
        assertEquals("curb", cue.antiRepeatKey)
    }

    @Test
    fun `bordure au dela de 6m silence`() {
        assertFalse(CurbSignal.of(listOf(centered("curb", 6.5f)), 512, 1138).detected)
    }

    @Test
    fun `bordure compte a rebours paliers`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 5f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(far, 1_000L, En)
        assertNotNull(watcher.onSignal(far, 1_100L, En))
        val near = CurbSignal(detected = true, meters = 1.5f, centerX = 0.5f, edge = CurbEdge.Up)
        assertNull(watcher.onSignal(near, 1_200L, En))
        assertEquals("Curb at 1.5 meters.", watcher.onSignal(near, 1_300L, En)!!.phrase)
    }

    @Test
    fun `bordure anti repetition 2s`() {
        val watcher = CurbWatcher()
        val signal = CurbSignal(detected = true, meters = 5f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(signal, 1_000L, En)
        assertNotNull(watcher.onSignal(signal, 1_100L, En))
        repeat(3) { watcher.onSignal(CurbSignal.none(), 1_200L + it * 100L, En) }
        watcher.onSignal(signal, 2_000L, En)
        assertNull(watcher.onSignal(signal, 2_100L, En))
        assertNotNull(watcher.onSignal(signal, 3_200L, En))
    }

    // Escaliers — niveau 6.

    @Test
    fun `escalier annonce standard niveau 6`() {
        val watcher = StairWatcher()
        val signal = StairSignal(detected = true, confidence = 0.9f, lineCount = 0, roiTop = 0, roiBottom = 0)
        watcher.onSignal(signal, 1_000L, En, StairDirection.Up, 0.2f)
        watcher.onSignal(signal, 1_100L, En, StairDirection.Up, 0.2f)
        val cue = watcher.onSignal(signal, 1_200L, En, StairDirection.Up, 0.2f)

        assertEquals("Stairs up, at 10 o'clock.", cue!!.phrase)
        assertEquals(6, cue.level)
        assertEquals("stairs", cue.antiRepeatKey)
    }

    @Test
    fun `escalier jalons 4 2 1 traverses`() {
        val watcher = StairWatcher()
        val signal = StairSignal(detected = true, confidence = 0.9f, lineCount = 5, roiTop = 0, roiBottom = 0)
        watcher.onSignal(signal, 1_000L, En, StairDirection.Unknown, null, 5.0f)
        watcher.onSignal(signal, 1_100L, En, StairDirection.Unknown, null, 5.0f)
        watcher.onSignal(signal, 1_200L, En, StairDirection.Unknown, null, 3.5f)
        watcher.onSignal(signal, 1_300L, En, StairDirection.Unknown, null, 3.5f)
        assertEquals("Stairs ahead at 4 meters.", watcher.onSignal(signal, 1_400L, En, StairDirection.Unknown, null, 3.5f)!!.phrase)
        watcher.onSignal(signal, 1_500L, En, StairDirection.Unknown, null, 1.8f)
        watcher.onSignal(signal, 1_600L, En, StairDirection.Unknown, null, 1.8f)
        assertEquals("Stairs ahead at 2 meters.", watcher.onSignal(signal, 1_700L, En, StairDirection.Unknown, null, 1.8f)!!.phrase)
        watcher.onSignal(signal, 1_800L, En, StairDirection.Unknown, null, 0.9f)
        watcher.onSignal(signal, 1_900L, En, StairDirection.Unknown, null, 0.9f)
        assertEquals("Stairs ahead at 1 meter.", watcher.onSignal(signal, 2_000L, En, StairDirection.Unknown, null, 0.9f)!!.phrase)
    }

    @Test
    fun `escalier nombre de marches dit`() {
        assertEquals("5 steps.", StairCountVoice().onTick(true, 5, 1_000L, En))
        assertEquals("1 step.", StairText.stepCount(1, En))
        assertEquals("5 marches.", StairText.stepCount(5, SpeechLanguage.Fr))
    }

    @Test
    fun `escalier anti repetition 2s`() {
        val voice = StairCountVoice()
        assertEquals("5 steps.", voice.onTick(true, 5, 1_000L, En))
        assertNull(voice.onTick(true, 5, 2_500L, En))
        assertEquals("5 steps.", voice.onTick(true, 5, 3_100L, En))
        val watcher = StairWatcher()
        val signal = StairSignal(detected = true, confidence = 0.9f, lineCount = 0, roiTop = 0, roiBottom = 0)
        watcher.onSignal(signal, 1_000L, En)
        watcher.onSignal(signal, 1_100L, En)
        assertNotNull(watcher.onSignal(signal, 1_200L, En))
        watcher.onSignal(StairSignal.none(), 1_300L, En)
        watcher.onSignal(signal, 2_000L, En)
        watcher.onSignal(signal, 2_100L, En)
        assertNull(watcher.onSignal(signal, 3_000L, En))
        assertNotNull(watcher.onSignal(signal, 3_300L, En))
    }

    // Grillage ouvert/fermé — niveau 4.

    @Test
    fun `grillage ferme niveau 4 sous 6m`() {
        val signal = FenceOpeningSignal.of(listOf(centered("fence", 5f)), 512, 1138)
        assertTrue(signal.detected)
        val watcher = FenceOpeningWatcher()
        assertNull(watcher.onSignal(signal, 1_000L, En))
        val cue = watcher.onSignal(signal, 1_100L, En)

        assertEquals("Fence closed ahead.", cue!!.phrase)
        assertEquals(4, cue.level)
        assertEquals("fence", cue.antiRepeatKey)
    }

    @Test
    fun `grillage hors portee 6m silence et cooldown 4s`() {
        assertFalse(FenceOpeningSignal.of(listOf(centered("fence", 6.5f)), 512, 1138).detected)
        val watcher = FenceOpeningWatcher()
        val signal = FenceOpeningSignal.of(listOf(centered("fence", 3f)), 512, 1138)
        watcher.onSignal(signal, 1_000L, En)
        assertNotNull(watcher.onSignal(signal, 1_100L, En))
        watcher.onSignal(signal, 4_000L, En)
        assertNull(watcher.onSignal(signal, 4_100L, En))
        assertNotNull(watcher.onSignal(signal, 5_200L, En))
    }

    // Passages piétons — niveau 8, 3 couches.

    @Test
    fun `passage annonce 1 fois par vue sous 6m`() {
        val watcher = CrossingApproachWatcher()
        assertNull(watcher.onFrame(0.5f, 5.5f, En))
        assertEquals("Crosswalk ahead, 6 meters away.", watcher.onFrame(0.5f, 5.5f, En))
        assertNull(watcher.onFrame(0.5f, 5.5f, En))
        assertNull(watcher.onFrame(null, null, En))
        watcher.onFrame(0.5f, 5.5f, En)
        assertNotNull(watcher.onFrame(0.5f, 5.5f, En))
        val far = CrossingApproachWatcher()
        assertNull(far.onFrame(0.5f, 6.5f, En))
        assertNull(far.onFrame(0.5f, 6.5f, En))
        assertNull(far.onFrame(0.5f, 6.5f, En))
        assertEquals(8, CrossingApproachWatcher.LEVEL)
        assertEquals("crosswalk-announce", CrossingApproachWatcher.KEY_ANNOUNCE)
    }

    @Test
    fun `passage alignement au changement`() {
        val watcher = CrossingLateralAnnouncer()
        assertEquals("Left.", watcher.onFrame(1_000L, 0.1f, En))
        assertNull(watcher.onFrame(2_000L, 0.1f, En))
        assertEquals("Right.", watcher.onFrame(3_000L, 0.9f, En))
        assertEquals("crosswalk-align", CrossingGuidance.KEY_ALIGN)
    }

    @Test
    fun `passage arrivee 1 fois`() {
        val watcher = CrossingArrivalWatcher()
        assertNull(watcher.onFrame(true, En))
        assertEquals("On the crosswalk.", watcher.onFrame(true, En)!!.phrase)
        assertNull(watcher.onFrame(true, En))
        assertNull(watcher.onFrame(false, En))
        watcher.onFrame(true, En)
        assertNotNull(watcher.onFrame(true, En))
        assertEquals("crosswalk-arrival", CrossingGuidance.KEY_ARRIVAL)
        assertEquals(8, CrossingGuidance.LEVEL)
    }

    // Trottoirs — niveau 10, inchangés.

    @Test
    fun `trottoir inchange parle au changement seul`() {
        val watcher = SidewalkWatcher()
        assertNull(watcher.onPosition(SidewalkPosition.Left, 1_000L, En))
        val cue = watcher.onPosition(SidewalkPosition.Left, 1_100L, En)
        assertEquals("Sidewalk on the left.", cue!!.phrase)
        assertEquals(10, cue.level)
        assertEquals("sidewalk", cue.antiRepeatKey)
        watcher.onPosition(SidewalkPosition.Left, 2_000L, En)
        assertNull(watcher.onPosition(SidewalkPosition.Left, 2_100L, En))
        assertNull(watcher.onPosition(SidewalkPosition.Absent, 3_000L, En))
        watcher.onPosition(SidewalkPosition.Right, 3_100L, En)
        assertEquals("Sidewalk on the right.", watcher.onPosition(SidewalkPosition.Right, 3_200L, En)!!.phrase)
    }

    // Voitures — textes EN (la détection est une autre mission).

    @Test
    fun `voiture presence seule sans cadran ni distance et pluriel`() {
        val car = VehicleStateText.nameOf("car")
        assertNotNull(car)
        assertEquals("Car moving.", VehicleStateText.movingPhrase(car!!, En))
        assertFalse(VehicleStateText.movingPhrase(car, En).contains("o'clock"))
        assertFalse(VehicleStateText.movingPhrase(car, En).contains("meter"))
        assertEquals("3 vehicles moving.", VehicleStateText.movingPluralPhrase(3, En))
        assertEquals("vehicle_car_moving", VehicleStateText.movingClipId(car))
        assertEquals("vehicle_car_moving", VehicleStateText.clipIdFor("Car moving."))
    }

    // Contrats partagés v4.

    @Test
    fun `niveaux et cles v4 des watchers dehors`() {
        assertEquals(2, HazardWatcher.LEVEL_PERSON)
        assertEquals(4, HazardWatcher.LEVEL_FENCE)
        assertEquals(7, HazardWatcher.LEVEL_POLE)
        assertEquals("human-soft", HazardWatcher.KEY_HUMAN_SOFT)
        assertEquals("human-imminent", HazardWatcher.KEY_HUMAN_IMMINENT)
        assertEquals("fence", HazardWatcher.KEY_FENCE)
        assertEquals("pole", HazardWatcher.KEY_POLE)
        assertEquals(5, CurbWatcher.LEVEL)
        assertEquals("curb", CurbWatcher.KEY_CURB)
        assertEquals(6, StairWatcher.LEVEL)
        assertEquals("stairs", StairWatcher.KEY_STAIRS)
        assertEquals(4, FenceOpeningWatcher.LEVEL)
        assertEquals("fence", FenceOpeningWatcher.KEY_FENCE)
        assertEquals(10, SidewalkWatcher.LEVEL)
        assertEquals("sidewalk", SidewalkWatcher.KEY_SIDEWALK)
        assertEquals(10f, HazardWatcher.PERSON_METERS, 0.001f)
        assertEquals(6f, HazardWatcher.HAZARD_METERS, 0.001f)
        assertEquals(6f, CurbWatcher.CURB_METERS, 0.001f)
        assertEquals(6f, FenceOpeningWatcher.RANGE_METERS, 0.001f)
    }
}
