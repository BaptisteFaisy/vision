package dev.sam3vision.core.traverse

import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le veto trafic force « attendez » même sur vert confirmé ; le vert seul fait
 * traverser. Bloque : l'approche (croissance, tau suivi) et la proximité sans
 * arrêt prouvé (< 15 m sténopé). Ne bloque plus : l'arrêt prouvé (mesuré
 * ≤ 15 m, centre quasi fixe, portée stable à 0,5 m, croissance sous le
 * tremblé 4 %, fond fixe) — une garée n'interdit pas la traversée. Focale de
 * test : 1000 px (une voiture de 200 px de haut sort à 7,25 m).
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
    fun `voiture proche sans approche - veto quand meme`() {
        val veto = TraverseVeto()
        val nearCar = detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))
        val decision = veto.update(true, listOf(nearCar), focalPx)
        assertTrue("proximité mesurée : veto (audit 31/10)", decision.veto)
        assertEquals(TraversePhase.ATTENDRE, decision.state)
        assertEquals("car", decision.blockingLabel)
        assertEquals(7.25f, decision.blockingMeters!!, 0.01f)
        assertTrue("objet proche : guidage gardé", decision.keepObstacleGuidance)
    }

    @Test
    fun `voiture proche en croissance - veto avec distance`() {
        val veto = TraverseVeto()
        veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 60f, 60f))), focalPx)
        val decision = veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))), focalPx)
        assertTrue(decision.veto)
        assertEquals(TraversePhase.ALERTE, decision.state)
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
    fun `score limite - 0_35 ignore meme en croissance, 0_36 bloque`() {
        val weak = TraverseVeto()
        weak.update(true, listOf(detection("car", 0.35f, listOf(0f, 0f, 200f, 200f))), focalPx)
        val ignored = weak.update(true, listOf(detection("car", 0.35f, listOf(0f, 0f, 220f, 220f))), focalPx)
        assertFalse("au seuil : ignoré malgré la croissance", ignored.veto)
        assertNull(ignored.blockingLabel)
        val strong = TraverseVeto()
        strong.update(true, listOf(detection("car", 0.36f, listOf(0f, 0f, 200f, 200f))), focalPx)
        val blocked = strong.update(true, listOf(detection("car", 0.36f, listOf(0f, 0f, 220f, 220f))), focalPx)
        assertTrue("au-dessus du seuil + croissance : veto", blocked.veto)
        assertEquals("car", blocked.blockingLabel)
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
    fun `guidage - vehicule non mesure garde, objet non mesure coupe`() {
        val veto = TraverseVeto()
        val car = veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))), focalPx = 0f)
        assertFalse("statique : pas de veto", car.veto)
        assertTrue("véhicule non mesuré : guidage gardé", car.keepObstacleGuidance)
        val person = TraverseVeto().update(true, listOf(detection("person", 0.9f, listOf(0f, 0f, 150f, 200f))), focalPx = 0f)
        assertFalse(person.keepObstacleGuidance)
    }

    @Test
    fun `veto pendant la traversee donne alerte puis reconfirmation`() {
        val veto = TraverseVeto()
        veto.update(true, emptyList(), focalPx)
        veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.TRAVERSER, veto.currentState)
        veto.update(true, listOf(detection("car", 0.8f, listOf(0f, 0f, 60f, 50f))), focalPx)
        val alert = veto.update(
            true,
            listOf(detection("car", 0.8f, listOf(0f, 0f, 200f, 250f))),
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
        veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 60f, 60f))), focalPx)
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
        assertTrue("approche non mesurée : guidage gardé, annoncé sans distance", growing.keepObstacleGuidance)
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
    fun `prompts de traversee bornes a dix huit vehicules en tete`() {
        val prompts = VehicleConcepts.promptsFor(listOf("traffic light", "person", "door", "zebra"))
        assertEquals(
            listOf(
                "car", "bus", "bicycle", "truck", "motorcycle",
                "traffic light", "person", "door", "zebra",
            ),
            prompts,
        )
        val full = VehicleConcepts.promptsFor((1..20).map { "objet$it" })
        assertEquals(LivePrompts.MAX, full.size)
        assertEquals(VehicleConcepts.PROMPTS, full.take(5))
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
    fun `fond qui defile - proximite statique, veto maintenu`() {
        // Boîte statique sous 15 m pendant un pan : elle tient sa place face à
        // la caméra (une garée dériverait) — proche ou doute bloque (audit 31/10).
        // Seule une croissance expliquée par le suivi caméra se tait.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val veto = TraverseVeto()
        val nearCar = detection("car", 0.9f, listOf(0f, 0f, 200f, 200f))
        var last: TraverseDecision? = null
        repeat(4) { index ->
            last = veto.update(true, listOf(nearCar), focalPx, atMs = index * 100L, motion = motion)
        }

        assertTrue("7,25 m statique sous pan : veto", last!!.veto)
        assertEquals("car", last.blockingLabel)
        assertEquals(7.25f, last.blockingMeters!!, 0.01f)
        assertTrue("objet proche : guidage gardé", last.keepObstacleGuidance)
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

    @Test
    fun `arret confirme a 7m - vert autorise des la deuxieme frame`() {
        // Ordre user du 01/11 : vert + immobile ≤ 15 m → on traverse.
        val veto = TraverseVeto()
        val box = listOf(220f, 140f, 420f, 340f) // centrée, 200 px de haut → 7,25 m
        val first = veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 0L)
        assertTrue("première frame : doute bloque", first.veto)
        val second = veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 100L)
        assertFalse("arrêt confirmé ≤ 15 m : le vert décide", second.veto)
        assertEquals(TraversePhase.PRET, second.state)
        val third = veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 200L)
        assertFalse(third.veto)
        assertEquals(TraversePhase.TRAVERSER, third.state)
    }

    @Test
    fun `arret confirme a 12m - vert autorise`() {
        val veto = TraverseVeto()
        val box = listOf(259f, 179f, 381f, 300f) // centrée, 121 px de haut → ~12 m
        veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 0L)
        val second = veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 100L)
        assertFalse("arrêt confirmé à 12 m : le vert décide", second.veto)
        assertEquals(TraversePhase.PRET, second.state)
    }

    @Test
    fun `garee hors couloir immobile - vert autorise sans pan`() {
        // Même boîte que le test pan (cx = 0,156, hors couloir) mais caméra
        // fixe : prouvée immobile, elle n'interdit pas la traversée.
        val veto = TraverseVeto()
        val box = listOf(0f, 0f, 200f, 200f)
        veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 0L)
        val second = veto.update(true, listOf(detection("car", 0.9f, box)), focalPx, 100L)
        assertFalse("immobile ≤ 15 m, fond fixe : le vert décide", second.veto)
        assertEquals(TraversePhase.PRET, second.state)
    }

    @Test
    fun `approche lente de face - croissance bloque malgre seau stable`() {
        // De face, centre fixe, seau « 12 » stable, mais boîte qui grandit :
        // ça roule, pas d'exemption d'arrêt.
        val veto = TraverseVeto()
        veto.update(true, listOf(detection("car", 0.9f, listOf(261f, 181f, 379f, 298f))), focalPx, 0L)
        val second = veto.update(true, listOf(detection("car", 0.9f, listOf(259f, 179f, 381f, 300f))), focalPx, 100L)
        assertTrue("croissance : approche, pas arrêt", second.veto)
        assertEquals("car", second.blockingLabel)
        assertEquals(11.98f, second.blockingMeters!!, 0.05f)
    }

    @Test
    fun `tremble de mesure a frontiere de seau - arret prouve quand meme`() {
        // 7,47 m ↔ 7,51 m : les seaux « 7 »/« 8 » alternent, la portée (±4 cm)
        // dit l'arrêt — le vert décide.
        val veto = TraverseVeto()
        val boxA = listOf(220f, 143f, 420f, 337f) // 200×194 → 7,47 m
        val boxB = listOf(219.5f, 143.5f, 420.5f, 336.5f) // 201×193 → 7,51 m
        veto.update(true, listOf(detection("car", 0.9f, boxA)), focalPx, 0L)
        val second = veto.update(true, listOf(detection("car", 0.9f, boxB)), focalPx, 100L)
        assertFalse("tremblé de mesure : arrêt prouvé", second.veto)
        assertEquals(TraversePhase.PRET, second.state)
        val third = veto.update(true, listOf(detection("car", 0.9f, boxA)), focalPx, 200L)
        assertFalse(third.veto)
        assertEquals(TraversePhase.TRAVERSER, third.state)
    }

    @Test
    fun `tremble de boite 2 pourcent - arret prouve quand meme`() {
        // +2 % d'aire, centre et portée stables : tremblé SAM, pas approche.
        val veto = TraverseVeto()
        val boxA = listOf(220f, 140f, 420f, 340f) // 200×200 → 7,25 m
        val boxB = listOf(219f, 139f, 421f, 341f) // 202×202 → 7,18 m
        veto.update(true, listOf(detection("car", 0.9f, boxA)), focalPx, 0L)
        val second = veto.update(true, listOf(detection("car", 0.9f, boxB)), focalPx, 100L)
        assertFalse("tremblé de boîte : arrêt prouvé", second.veto)
        assertEquals(TraversePhase.PRET, second.state)
        val third = veto.update(true, listOf(detection("car", 0.9f, boxB)), focalPx, 200L)
        assertFalse(third.veto)
        assertEquals(TraversePhase.TRAVERSER, third.state)
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
    fun `des update concurrents ne plantent pas le veto`() {
        // Non-régression : instance unique par `LiveProbe`, alimentée par des
        // `runFrame` qui se recouvrent (même classe de race que
        // `PersonTracker.sweep`, crash constaté sur émulateur le 01/11).
        val veto = TraverseVeto()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = List(8) { t ->
            Thread {
                start.await()
                try {
                    repeat(300) { i ->
                        veto.update(
                            lightGreen = true,
                            detections = listOf(youngCarDetection(i % 20)),
                            focalPx = focalPx,
                            atMs = (t * 300L + i) * 40L,
                        )
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }
        assertTrue("plantage concurrent : ${errors.peek()}", errors.isEmpty())
    }
}
