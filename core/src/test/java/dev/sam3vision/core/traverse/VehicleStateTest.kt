package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * États des véhicules moteurs : en mouvement (tau confirmé ou croissance)
 * jusqu'à 8 m, à l'arrêt (dérive faible, seau stable) jusqu'à 3 m dans le
 * couloir, les cycles exclus (annoncés par `CycleWatcher`). Séquences
 * synthétiques, miroir de `VehicleApproachTest`.
 */
class VehicleStateTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `alerte mouvement exposee, arret seul = rien`() {
        val moving = VehicleStateWatcher()
        assertNull(moving.lastMovingAlertMs)
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            moving.update(
                listOf(det("car", box(width))),
                listOf(reading("car", 5f, box(width))),
                atMs = index * 100L,
            )
        }
        assertEquals(200L, moving.lastMovingAlertMs)

        val stopped = VehicleStateWatcher()
        repeat(3) { index ->
            stopped.update(
                listOf(det("car", box(100f))),
                listOf(reading("car", 2f, box(100f))),
                atMs = index * 100L,
            )
        }
        assertNull("arrêt seul : pas d'alerte mouvement", stopped.lastMovingAlertMs)

        moving.reset()
        assertNull(moving.lastMovingAlertMs)
    }

    @Test
    fun `voiture qui approche - mouvement a la 3e frame, urgent au centre`() {
        val watcher = VehicleStateWatcher()
        assertNull(watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = 0L))
        assertNull("croissance seule : confirmation en cours", watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = 100L))
        val third = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 5f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Voiture en mouvement, à 5 mètres.", third!!.phrase)
        assertEquals(5f, third.meters!!, 0.001f)
        assertTrue("tau < 3 s + proche + centre : urgent", third.urgent)
    }

    @Test
    fun `sans datation - croissance seule, jamais urgent`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = -1L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = -1L)
        val third = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 5f, box(120f))),
            atMs = -1L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Voiture en mouvement, à 5 mètres.", third!!.phrase)
        assertFalse("sans tau : doux", third.urgent)
    }

    @Test
    fun `voiture arretee - annonce a la 3e frame`() {
        val watcher = VehicleStateWatcher()
        assertNull(watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 0L))
        assertNull(watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 100L))
        val third = watcher.update(
            listOf(det("car", box(100f))),
            listOf(reading("car", 2f, box(100f))),
            atMs = 200L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Voiture à l'arrêt, à 2 mètres.", third!!.phrase)
        assertFalse(third.urgent)
    }

    @Test
    fun `seau instable - doute, silence`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 0L)
        assertNull(
            "seau 2 m → 3 m : pas d'arrêt",
            watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2.6f, box(100f))), atMs = 100L),
        )
    }

    @Test
    fun `sans mesure - mouvement et arret a l'heure`() {
        val moving = VehicleStateWatcher()
        moving.update(listOf(det("car", box(100f))), emptyList(), atMs = 0L)
        moving.update(listOf(det("car", box(110f))), emptyList(), atMs = 100L)
        val third = moving.update(listOf(det("car", box(120f))), emptyList(), atMs = 200L)

        assertEquals("Car moving, at 12 o'clock.", third!!.phrase)
        assertNull(third.meters)
        assertFalse(third.urgent)

        val stopped = VehicleStateWatcher()
        stopped.update(listOf(det("car", box(100f))), emptyList(), atMs = 0L)
        stopped.update(listOf(det("car", box(100f))), emptyList(), atMs = 100L)
        val parked = stopped.update(listOf(det("car", box(100f))), emptyList(), atMs = 200L)

        assertEquals("Car stopped, at 12 o'clock.", parked!!.phrase)
        assertNull(parked.meters)
        assertFalse(parked.urgent)
        assertNull(parked.safetyPhrase)
    }

    @Test
    fun `cycles confies au CycleWatcher - silence ici`() {
        val watcher = VehicleStateWatcher()
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            assertNull(
                watcher.update(
                    listOf(det("bicycle", box(width))),
                    listOf(reading("bicycle", 5f, box(width))),
                    atMs = index * 100L,
                ),
            )
        }
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            assertNull(
                watcher.update(
                    listOf(det("trottinette", box(width))),
                    listOf(reading("trottinette", 5f, box(width))),
                    atMs = 1_000L + index * 100L,
                ),
            )
        }
    }

    @Test
    fun `mouvement a 16m - hors portee, silence`() {
        val movingFar = VehicleStateWatcher()
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            assertNull(
                "mouvement à 16 m : hors portée",
                movingFar.update(
                    listOf(det("car", box(width))),
                    listOf(reading("car", 16f, box(width))),
                    atMs = index * 100L,
                ),
            )
        }
    }

    @Test
    fun `voiture garee a 5m et sur le cote - annonce a l'arret`() {
        val mid = VehicleStateWatcher()
        repeat(2) { index ->
            mid.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = index * 100L)
        }
        val far = mid.update(
            listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))),
            atMs = 200L, language = SpeechLanguage.Fr,
        )
        assertEquals("Voiture à l'arrêt, à 5 mètres.", far!!.phrase)

        val sideBox = box(100f, cx = 64f)
        val edge = VehicleStateWatcher()
        repeat(2) { index ->
            edge.update(listOf(det("car", sideBox)), listOf(reading("car", 2f, sideBox)), atMs = index * 100L)
        }
        val parked = edge.update(
            listOf(det("car", sideBox)), listOf(reading("car", 2f, sideBox)),
            atMs = 200L, language = SpeechLanguage.Fr,
        )
        assertEquals("Voiture à l'arrêt, à 2 mètres.", parked!!.phrase)
    }

    @Test
    fun `voiture qui traverse - mouvement lateral annonce`() {
        // Fond fixe (caméra immobile) + boîte qui glisse à taille stable :
        // la traversée latérale (verdict approche) annonce dès confirmation.
        val motion = FrameMotion(MotionSample(0f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        val frames = listOf(200f, 240f, 280f, 320f, 360f).map { box(100f, cx = it) }
        frames.forEachIndexed { index, frame ->
            val cue = watcher.update(
                listOf(det("car", frame)), listOf(reading("car", 12f, frame)),
                atMs = index * 100L, language = SpeechLanguage.Fr, motion = motion,
            )
            if (index < 3) assertNull(cue)
            else if (index == 3) assertEquals("Voiture en mouvement, à 12 mètres.", cue!!.phrase)
            else assertNull("cooldown mouvement", cue)
        }
    }

    @Test
    fun `urgent exige tau proche et centre`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f, cx = 100f))), listOf(reading("car", 5f, box(100f, cx = 100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f, cx = 100f))), listOf(reading("car", 5f, box(110f, cx = 100f))), atMs = 100L)
        val third = watcher.update(
            listOf(det("car", box(120f, cx = 100f))),
            listOf(reading("car", 5f, box(120f, cx = 100f))),
            atMs = 200L,
        )

        assertEquals("Car moving, 5 meters away.", third!!.phrase)
        assertFalse("approche à 9 h : doux", third.urgent)
    }

    @Test
    fun `cooldowns - 4s mouvement, 10s arret`() {
        val moving = VehicleStateWatcher()
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            moving.update(listOf(det("car", box(width))), listOf(reading("car", 5f, box(width))), atMs = index * 100L)
        }
        moving.update(listOf(det("car", box(130f))), listOf(reading("car", 5f, box(130f))), atMs = 1_000L)
        assertNull("mouvement : 900 ms < 4 s", moving.update(listOf(det("car", box(140f))), listOf(reading("car", 5f, box(140f))), atMs = 1_100L))
        val replay = moving.update(listOf(det("car", box(150f))), listOf(reading("car", 5f, box(150f))), atMs = 4_300L)
        assertEquals("Car moving, 5 meters away.", replay!!.phrase)

        val stopped = VehicleStateWatcher()
        repeat(3) { index ->
            stopped.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = index * 100L)
        }
        stopped.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 1_000L)
        assertNull("arrêt : 900 ms < 10 s", stopped.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 1_100L))
        val replayStopped = stopped.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 10_400L)
        assertEquals("Car stopped, 2 meters away.", replayStopped!!.phrase)
    }

    @Test
    fun `phrases FR EN`() {
        val voiture = VehicleStateText.nameOf("voiture")!!
        val bus = VehicleStateText.nameOf("bus")!!
        assertEquals("Voiture", voiture.fr)
        assertEquals("Car", voiture.en)
        assertEquals(
            "Voiture en mouvement, à 5 mètres.",
            VehicleStateText.movingPhrase(voiture, 5f, null, SpeechLanguage.Fr),
        )
        assertEquals(
            "Voiture en mouvement, à midi.",
            VehicleStateText.movingPhrase(voiture, null, 0.5f, SpeechLanguage.Fr),
        )
        assertEquals(
            "Car moving, 5 meters away.",
            VehicleStateText.movingPhrase(voiture, 5f, null, SpeechLanguage.En),
        )
        assertEquals(
            "Voiture à l'arrêt, à 2 mètres.",
            VehicleStateText.stoppedPhrase(voiture, 2f, null, SpeechLanguage.Fr),
        )
        assertEquals(
            "Bus stopped, 3 meters away.",
            VehicleStateText.stoppedPhrase(bus, 3f, null, SpeechLanguage.En),
        )
        assertEquals("Camion", VehicleStateText.nameOf("camion")!!.fr)
        assertEquals("Motorcycle", VehicleStateText.nameOf("moto")!!.en)
    }

    @Test
    fun `noms inconnus - personne et vide ignores`() {
        assertNull(VehicleStateText.nameOf("person"))
        assertNull(VehicleStateText.nameOf("door"))
        assertNull(VehicleStateText.nameOf(""))
    }

    @Test
    fun `reset - oublie tout`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = 100L)
        watcher.reset()

        assertNull(watcher.lastAlertMs)
        assertNull(
            watcher.update(listOf(det("car", box(120f))), listOf(reading("car", 5f, box(120f))), atMs = 200L),
        )
    }

    @Test
    fun `mouvement - phrase securite stop voiture en mouvement`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = 100L)
        val moving = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 5f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.En,
        )

        assertEquals("Stop, car moving.", moving!!.safetyPhrase)
        assertEquals(
            "Stop, bus moving.",
            VehicleStateText.safetyPhrase(VehicleStateText.nameOf("bus")!!, SpeechLanguage.En),
        )
    }

    @Test
    fun `mouvement francais - stop voiture, arret sans phrase securite`() {
        val moving = VehicleStateWatcher()
        moving.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = 0L)
        moving.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = 100L)
        val cue = moving.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 5f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Stop, voiture en mouvement.", cue!!.safetyPhrase)

        val stopped = VehicleStateWatcher()
        stopped.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 0L)
        stopped.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 100L)
        val parked = stopped.update(
            listOf(det("car", box(100f))),
            listOf(reading("car", 2f, box(100f))),
            atMs = 200L,
            language = SpeechLanguage.Fr,
        )

        assertNull(parked!!.safetyPhrase)
    }

    @Test
    fun `mouvement loin - info anglaise douce sans stop`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 12f, box(100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 12f, box(110f))), atMs = 100L)
        val cue = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 12f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.En,
        )

        assertEquals("Car moving, 12 meters away.", cue!!.phrase)
        assertNull(cue.safetyPhrase)
        assertFalse("loin du centre du danger : doux", cue.urgent)
    }

    @Test
    fun `mouvement proche apres info - stop sans attendre le cooldown`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 12f, box(100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 12f, box(110f))), atMs = 100L)
        val info = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 12f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.En,
        )
        assertNull(info!!.safetyPhrase)

        watcher.update(listOf(det("car", box(130f))), listOf(reading("car", 5f, box(130f))), atMs = 300L)
        val stop = watcher.update(
            listOf(det("car", box(140f))),
            listOf(reading("car", 5f, box(140f))),
            atMs = 400L,
            language = SpeechLanguage.En,
        )
        assertEquals("Stop, car moving.", stop!!.safetyPhrase)

        watcher.update(listOf(det("car", box(150f))), listOf(reading("car", 12f, box(150f))), atMs = 500L)
        assertNull(
            "info juste après un stop : silence",
            watcher.update(listOf(det("car", box(160f))), listOf(reading("car", 12f, box(160f))), atMs = 600L),
        )
    }

    @Test
    fun `mouvement proche - cooldown 4s entre deux stops`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = 100L)
        val first = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 5f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.En,
        )
        assertEquals("Stop, car moving.", first!!.safetyPhrase)

        watcher.update(listOf(det("car", box(130f))), listOf(reading("car", 5f, box(130f))), atMs = 300L)
        assertNull(
            "stop en cooldown : silence",
            watcher.update(listOf(det("car", box(140f))), listOf(reading("car", 5f, box(140f))), atMs = 400L),
        )

        val second = watcher.update(
            listOf(det("car", box(150f))),
            listOf(reading("car", 5f, box(150f))),
            atMs = 4_400L,
            language = SpeechLanguage.En,
        )
        assertEquals("Stop, car moving.", second!!.safetyPhrase)
    }

    @Test
    fun `deux types en mouvement - pluriel generique et stop pluriel`() {
        val watcher = VehicleStateWatcher()
        var cue: VehicleStateCue? = null
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            cue = watcher.update(
                listOf(det("car", box(width)), det("bus", box(width, cx = 420f))),
                listOf(reading("car", 5f, box(width)), reading("bus", 6f, box(width, cx = 420f))),
                atMs = index * 100L,
                language = SpeechLanguage.En,
            )
        }

        assertEquals("2 vehicles moving, 5 meters away.", cue!!.phrase)
        assertEquals("Stop, 2 vehicles moving.", cue.safetyPhrase)
        assertEquals(2, cue.movingCount)
        assertEquals(
            "2 véhicules en mouvement, à 5 mètres.",
            VehicleStateText.movingPluralPhrase(2, 5f, null, SpeechLanguage.Fr),
        )
        assertEquals(
            "2 vehicles moving, at 12 o'clock.",
            VehicleStateText.movingPluralPhrase(2, null, 0.5f, SpeechLanguage.En),
        )
        assertEquals(
            "Stop, 3 véhicules en mouvement.",
            VehicleStateText.safetyPluralPhrase(3, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `flag moving distingue le roule de l'arrete`() {
        val rolling = VehicleStateWatcher()
        rolling.update(listOf(det("car", box(100f))), listOf(reading("car", 12f, box(100f))), atMs = 0L)
        rolling.update(listOf(det("car", box(110f))), listOf(reading("car", 12f, box(110f))), atMs = 100L)
        val moving = rolling.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 12f, box(120f))),
            atMs = 200L,
        )

        assertTrue(moving!!.moving)

        val parked = VehicleStateWatcher()
        parked.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 0L)
        parked.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = 100L)
        val stopped = parked.update(
            listOf(det("car", box(100f))),
            listOf(reading("car", 2f, box(100f))),
            atMs = 200L,
        )

        assertFalse(stopped!!.moving)
    }
}
