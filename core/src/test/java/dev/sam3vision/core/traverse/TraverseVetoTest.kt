package dev.sam3vision.core.traverse

import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le veto trafic force « attendez » même sur vert confirmé ; le vert seul fait
 * traverser. Focale de test : 1000 px (une voiture de 200 px de haut sort à 7,25 m).
 */
class TraverseVetoTest {

    private val focalPx = 1000f

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, width = 640, height = 480)

    @Test
    fun `vert seul fait traverser en deux frames`() {
        val veto = TraverseVeto()
        val first = veto.update(lightGreen = true, detections = emptyList(), focalPx = focalPx)
        assertEquals(TraversePhase.PRET, first.state)
        assertEquals("ready", first.cue)
        assertFalse(first.veto)
        val second = veto.update(lightGreen = true, detections = emptyList(), focalPx = focalPx)
        assertEquals(TraversePhase.TRAVERSER, second.state)
        assertEquals("cross", second.cue)
        assertFalse(second.keepObstacleGuidance)
    }

    @Test
    fun `vert plus voiture proche force attendez`() {
        val veto = TraverseVeto()
        val nearCar = detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))
        val decision = veto.update(true, listOf(nearCar), focalPx)
        assertTrue(decision.veto)
        assertEquals(TraversePhase.ATTENDRE, decision.state)
        assertEquals("wait", decision.cue)
        assertEquals("car", decision.blockingLabel)
        assertEquals(7.25f, decision.blockingMeters!!, 0.01f)
        assertTrue(decision.keepObstacleGuidance)
    }

    @Test
    fun `voiture qui approche au loin force attendez des la deuxieme frame`() {
        val veto = TraverseVeto()
        val far = detection("car", 0.9f, listOf(0f, 0f, 60f, 50f))
        val first = veto.update(true, listOf(far), focalPx)
        assertFalse(first.veto)
        assertEquals(TraversePhase.PRET, first.state)
        val nearer = detection("car", 0.9f, listOf(0f, 0f, 80f, 60f))
        val second = veto.update(true, listOf(nearer), focalPx)
        assertTrue(second.veto)
        assertEquals("wait", second.cue)
        assertEquals(24.17f, second.blockingMeters!!, 0.05f)
    }

    @Test
    fun `voiture qui s eloigne au loin ne bloque pas`() {
        val veto = TraverseVeto()
        veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 80f, 60f))), focalPx)
        val second = veto.update(
            true,
            listOf(detection("car", 0.9f, listOf(0f, 0f, 60f, 50f))),
            focalPx,
        )
        assertFalse(second.veto)
        assertEquals(TraversePhase.TRAVERSER, second.state)
    }

    @Test
    fun `score faible ne declenche pas de veto`() {
        val veto = TraverseVeto()
        val nearCar = detection("car", 0.5f, listOf(0f, 0f, 200f, 200f))
        val decision = veto.update(true, listOf(nearCar), focalPx)
        assertFalse(decision.veto)
        assertEquals(TraversePhase.PRET, decision.state)
        assertNull(decision.blockingLabel)
    }

    @Test
    fun `pieton proche ne fait pas veto mais garde le guidage obstacles`() {
        val veto = TraverseVeto()
        val nearPerson = detection("person", 0.9f, listOf(0f, 0f, 150f, 200f))
        val decision = veto.update(true, listOf(nearPerson), focalPx)
        assertFalse(decision.veto)
        assertEquals(TraversePhase.PRET, decision.state)
        assertTrue(decision.keepObstacleGuidance)
    }

    @Test
    fun `veto pendant la traversee donne alerte puis reconfirmation`() {
        val veto = TraverseVeto()
        veto.update(true, emptyList(), focalPx)
        veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.TRAVERSER, veto.currentState)
        val alert = veto.update(
            true,
            listOf(detection("bus", 0.8f, listOf(0f, 0f, 200f, 250f))),
            focalPx,
        )
        assertEquals(TraversePhase.ALERTE, alert.state)
        assertEquals("wait", alert.cue)
        assertTrue(alert.veto)
        val reconfirmed = veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.PRET, reconfirmed.state)
        val resumed = veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.TRAVERSER, resumed.state)
    }

    @Test
    fun `veto juste avant de partir donne alerte puis reconfirmation`() {
        val veto = TraverseVeto()
        veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.PRET, veto.currentState)
        val alert = veto.update(
            true,
            listOf(detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))),
            focalPx,
        )
        assertEquals(TraversePhase.ALERTE, alert.state)
        assertEquals("wait", alert.cue)
        assertTrue(alert.veto)
        val reconfirmed = veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.PRET, reconfirmed.state)
        val resumed = veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.TRAVERSER, resumed.state)
    }

    @Test
    fun `feu rouge sans vehicule attend`() {
        val veto = TraverseVeto()
        val decision = veto.update(false, emptyList(), focalPx)
        assertEquals(TraversePhase.ATTENDRE, decision.state)
        assertEquals("wait", decision.cue)
        assertFalse(decision.veto)
    }

    @Test
    fun `sans focale seule l approche compte`() {
        val veto = TraverseVeto()
        veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 60f, 50f))), focalPx = 0f)
        val growing = veto.update(
            true,
            listOf(detection("car", 0.9f, listOf(0f, 0f, 80f, 60f))),
            focalPx = 0f,
        )
        assertTrue(growing.veto)
        assertNull(growing.blockingMeters)
        assertFalse(growing.keepObstacleGuidance)
    }

    @Test
    fun `boite degeneree et reset ne font pas crasher`() {
        val veto = TraverseVeto()
        val broken = veto.update(true, listOf(detection("car", 0.9f, emptyList())), focalPx)
        assertFalse(broken.veto)
        veto.reset()
        assertEquals(TraversePhase.ATTENDRE, veto.currentState)
        val fresh = veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.PRET, fresh.state)
    }

    @Test
    fun `prompts de traversee bornes a dix sept vehicules en tete`() {
        val prompts = VehicleConcepts.promptsFor(listOf("traffic light", "person", "door", "zebra"))
        assertEquals(
            listOf(
                "car", "bus", "bicycle",
                "traffic light", "person", "door", "zebra",
            ),
            prompts,
        )
        val full = VehicleConcepts.promptsFor((1..20).map { "objet$it" })
        assertEquals(LivePrompts.MAX, full.size)
        assertEquals(VehicleConcepts.PROMPTS, full.take(3))
        assertEquals(VehicleConcepts.PROMPTS, VehicleConcepts.promptsFor())
    }

    @Test
    fun `isVehicle reconnait francais anglais et pluriels`() {
        assertTrue(VehicleConcepts.isVehicle("car"))
        assertTrue(VehicleConcepts.isVehicle("Cars"))
        assertTrue(VehicleConcepts.isVehicle("bus"))
        assertTrue(VehicleConcepts.isVehicle("truck"))
        assertTrue(VehicleConcepts.isVehicle("motorcycle"))
        assertTrue(VehicleConcepts.isVehicle("bicycle"))
        assertTrue(VehicleConcepts.isVehicle("scooter"))
        assertTrue(VehicleConcepts.isVehicle("Scooters"))
        assertTrue(VehicleConcepts.isVehicle("electric scooter"))
        assertTrue(VehicleConcepts.isVehicle("voiture"))
        assertTrue(VehicleConcepts.isVehicle("Voitures"))
        assertTrue(VehicleConcepts.isVehicle("camion"))
        assertTrue(VehicleConcepts.isVehicle("moto"))
        assertTrue(VehicleConcepts.isVehicle("vélo"))
        assertTrue(VehicleConcepts.isVehicle("trottinette"))
        assertTrue(VehicleConcepts.isVehicle("Trottinettes"))
        assertTrue(VehicleConcepts.isVehicle("trottinette électrique"))
        assertFalse(VehicleConcepts.isVehicle("person"))
        assertFalse(VehicleConcepts.isVehicle("door"))
        assertFalse(VehicleConcepts.isVehicle(""))
    }

    @Test
    fun `approche suivie - le TTC remonte dans la decision`() {
        val veto = TraverseVeto()
        val first = veto.update(true, listOf(detection("car", 0.9f, listOf(270f, 210f, 370f, 270f))), focalPx, 0L)
        veto.update(true, listOf(detection("car", 0.9f, listOf(265f, 207f, 375f, 273f))), focalPx, 100L)
        val third = veto.update(true, listOf(detection("car", 0.9f, listOf(260f, 204f, 380f, 276f))), focalPx, 200L)

        assertFalse("piste jeune : pas de TTC", first.veto)
        assertNull(first.approachTtcSeconds)
        assertTrue(third.veto)
        assertNotNull("rapprochement confirmé suivi", third.approachTtcSeconds)
    }

    @Test
    fun `fond qui defile - croissance tue, pas de veto`() {
        // Croissance + dérive suivies de la caméra : suivi et instantané de
        // surface se taisent (focale nulle : pas de distance).
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val bare = TraverseVeto()
        val aided = TraverseVeto()
        var bareLast: TraverseDecision? = null
        var aidedLast: TraverseDecision? = null
        repeat(13) { index ->
            val box = growingBox(index)
            val atMs = index * 100L
            bareLast = bare.update(true, listOf(detection("car", 0.9f, box)), focalPx = 0f, atMs = atMs)
            aidedLast = aided.update(true, listOf(detection("car", 0.9f, box)), focalPx = 0f, atMs = atMs, motion = motion)
        }

        assertNotNull("sans fond, le suivi voit l'approche", bareLast!!.approachTtcSeconds)
        assertNull("avec fond, la piste suit la caméra", aidedLast!!.approachTtcSeconds)
        assertFalse("croissance de clé tue : pas de veto", aidedLast.veto)
        assertNull(aidedLast.blockingLabel)
    }

    @Test
    fun `fond qui defile - la proximite mesuree ne se tait jamais`() {
        // Même sous caméra, un véhicule mesuré sous 15 m bloque toujours.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val veto = TraverseVeto()
        val nearCar = detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))
        repeat(3) { index ->
            veto.update(true, listOf(nearCar), focalPx, atMs = index * 100L, motion = motion)
        }
        val last = veto.update(true, listOf(nearCar), focalPx, atMs = 300L, motion = motion)

        assertTrue("proximité 7,25 m : veto", last.veto)
        assertEquals("car", last.blockingLabel)
    }

    @Test
    fun `fond qui defile - jeune piste non evaluee, veto maintenu`() {
        // Bus évalué caméra + voiture qui apparaît en croissance : la clé
        // jeune, jamais jugée, ne figure pas aux tues — son veto tient.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val veto = TraverseVeto()
        var last: TraverseDecision? = null
        repeat(13) { index ->
            val detections = mutableListOf(driftingBusDetection(index))
            if (index >= 11) detections.add(0, youngCarDetection(index - 11))
            last = veto.update(true, detections, focalPx = 0f, atMs = index * 100L, motion = motion)
        }

        assertTrue("croissance jeune non jugée : veto", last!!.veto)
        assertEquals("car", last.blockingLabel)
    }

    /** Boîte qui grandit (+10 px/frame) en dérivant (+5 px/frame). */
    private fun growingBox(index: Int): List<Float> {
        val width = 100f + index * 10f
        val cx = 320f + index * 5f
        return listOf(cx - width / 2f, 240f - width * 0.3f, cx + width / 2f, 240f + width * 0.3f)
    }

    /** Bus à largeur constante qui dérive comme le fond (+5 px/frame). */
    private fun driftingBusDetection(index: Int): Detection {
        val cx = 500f + index * 5f
        return detection("bus", 0.9f, listOf(cx - 50f, 300f, cx + 50f, 360f))
    }

    /** Voiture qui apparaît en croissance (2 frames : piste jeune, jamais évaluée). */
    private fun youngCarDetection(frame: Int): Detection {
        val width = 100f + frame * 20f
        return detection("car", 0.9f, listOf(320f - width / 2f, 240f - width * 0.3f, 320f + width / 2f, 240f + width * 0.3f))
    }

    @Test
    fun `personne non mesurable garde le guidage obstacles`() {
        val veto = TraverseVeto()
        val person = detection("person", 0.9f, listOf(300f, 200f, 340f, 400f))

        assertTrue(veto.update(true, listOf(person), 0f).keepObstacleGuidance)
    }

    @Test
    fun `vehicule non mesurable ne garde pas le guidage`() {
        val veto = TraverseVeto()
        val car = detection("car", 0.9f, listOf(300f, 200f, 340f, 400f))

        assertFalse(veto.update(true, listOf(car), 0f).keepObstacleGuidance)
    }
}
