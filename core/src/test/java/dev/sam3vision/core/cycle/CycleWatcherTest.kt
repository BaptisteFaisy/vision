package dev.sam3vision.core.cycle

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Annonces cycles : le mouvement parle à l'heure jusqu'à 8 m, l'arrêt parle
 * dans le couloir jusqu'à 3 m, le doute se tait. Séquences synthétiques.
 */
class CycleWatcherTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Vélo 100×60 px dont le bord gauche est à [x1] (bande médiane). */
    private fun bikeBox(x1: Float): List<Float> = listOf(x1, 210f, x1 + 100f, 270f)

    private fun centered(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `velo en mouvement proche - attention a l'heure, sans urgence laterale`() {
        val watcher = CycleWatcher()
        val boxes = listOf(bikeBox(430f), bikeBox(450f), bikeBox(470f), bikeBox(490f))
        var cue: CycleCue? = null
        boxes.forEachIndexed { index, box ->
            cue = watcher.update(
                listOf(det("bicycle", box)),
                listOf(reading("bicycle", 5f, box)),
                atMs = index * 100L,
                language = SpeechLanguage.Fr,
            )
            if (index < 3) assertNull("vitesse puis confirmation : silence", cue)
        }

        assertEquals("Attention, vélo en mouvement.", cue!!.phrase)
        assertEquals(5f, cue.meters!!, 0.001f)
        assertFalse("latéral seul, pas d'approche : doux", cue.urgent)
    }

    @Test
    fun `trottinette arretee - annonce a l'heure`() {
        val watcher = CycleWatcher()
        val box = centered(100f)
        var cue: CycleCue? = null
        repeat(4) { index ->
            cue = watcher.update(
                listOf(det("scooter", box)),
                listOf(reading("scooter", 2f, box)),
                atMs = index * 100L,
                language = SpeechLanguage.Fr,
            )
            if (index < 3) assertNull(cue)
        }

        assertEquals("Trottinette arrêtée, à midi.", cue!!.phrase)
        assertFalse(cue.urgent)
    }

    @Test
    fun `approche au centre - urgent`() {
        val watcher = CycleWatcher()
        var cue: CycleCue? = null
        listOf(100f, 110f, 120f, 130f).forEachIndexed { index, width ->
            val box = centered(width)
            cue = watcher.update(
                listOf(det("bicycle", box)),
                listOf(reading("bicycle", 5f, box)),
                atMs = index * 100L,
                language = SpeechLanguage.Fr,
            )
        }

        assertEquals("Attention, vélo en mouvement.", cue!!.phrase)
        assertTrue("croissance + proche + centre : urgent", cue.urgent)
    }

    @Test
    fun `hors portee et hors couloir - silence`() {
        val movingFar = CycleWatcher()
        listOf(bikeBox(430f), bikeBox(450f), bikeBox(470f), bikeBox(490f)).forEachIndexed { index, box ->
            val cue = movingFar.update(
                listOf(det("bicycle", box)),
                listOf(reading("bicycle", 9f, box)),
                atMs = index * 100L,
            )
            assertNull("mouvement à 9 m : hors portée", cue)
        }
        val stoppedSide = CycleWatcher()
        val sideBox = listOf(14f, 210f, 114f, 270f)
        repeat(4) { index ->
            val cue = stoppedSide.update(
                listOf(det("bicycle", sideBox)),
                listOf(reading("bicycle", 2f, sideBox)),
                atMs = index * 100L,
            )
            assertNull("arrêt hors couloir : silence", cue)
        }
    }

    @Test
    fun `sans mesure - mouvement a l'heure, arret muet`() {
        val moving = CycleWatcher()
        var cue: CycleCue? = null
        listOf(bikeBox(430f), bikeBox(450f), bikeBox(470f), bikeBox(490f)).forEachIndexed { index, box ->
            cue = moving.update(listOf(det("bicycle", box)), emptyList(), atMs = index * 100L)
        }
        assertEquals("Attention, bicycle moving.", cue!!.phrase)
        assertNull(cue.meters)

        val stopped = CycleWatcher()
        repeat(4) { index ->
            assertNull(
                "arrêt non mesuré : jamais inventé",
                stopped.update(listOf(det("bicycle", bikeBox(270f))), emptyList(), atMs = index * 100L),
            )
        }
    }

    @Test
    fun `cooldown mouvement - 4s puis reparle`() {
        val watcher = CycleWatcher()
        listOf(bikeBox(430f), bikeBox(450f), bikeBox(470f), bikeBox(490f)).forEachIndexed { index, box ->
            watcher.update(listOf(det("bicycle", box)), listOf(reading("bicycle", 5f, box)), atMs = index * 100L)
        }
        assertNull(watcher.update(listOf(det("bicycle", bikeBox(510f))), listOf(reading("bicycle", 5f, bikeBox(510f))), atMs = 1_000L))
        assertNull(
            "mouvement : 800 ms < 4 s",
            watcher.update(listOf(det("bicycle", bikeBox(530f))), listOf(reading("bicycle", 5f, bikeBox(530f))), atMs = 1_100L),
        )
        // Après le trou : piste rattrapée ou jeune reconfirmée, une seule annonce.
        var replay: CycleCue? = null
        listOf(4_400L to 550f, 4_500L to 570f, 4_600L to 590f, 4_700L to 610f).forEach { (atMs, x1) ->
            val box = bikeBox(x1)
            replay = watcher.update(listOf(det("bicycle", box)), listOf(reading("bicycle", 5f, box)), atMs = atMs) ?: replay
        }
        assertEquals("Attention, bicycle moving.", replay!!.phrase)
    }

    @Test
    fun `cooldown arret - 10s puis reparle`() {
        val watcher = CycleWatcher()
        val box = centered(100f)
        repeat(4) { index ->
            watcher.update(listOf(det("scooter", box)), listOf(reading("scooter", 2f, box)), atMs = index * 100L)
        }
        watcher.update(listOf(det("scooter", box)), listOf(reading("scooter", 2f, box)), atMs = 1_000L)
        assertNull(
            "arrêt : 800 ms < 10 s",
            watcher.update(listOf(det("scooter", box)), listOf(reading("scooter", 2f, box)), atMs = 1_100L),
        )
        // Le statique se rattrape après le trou : une seule annonce.
        var replay: CycleCue? = null
        listOf(10_400L, 10_500L).forEach { atMs ->
            replay = watcher.update(listOf(det("scooter", box)), listOf(reading("scooter", 2f, box)), atMs = atMs) ?: replay
        }
        assertEquals("Scooter stopped, at 12 o'clock.", replay!!.phrase)
    }

    @Test
    fun `phrases FR EN`() {
        val velo = CycleConcepts.nameOf("bicycle")!!
        val trottinette = CycleConcepts.nameOf("trottinette")!!
        assertEquals("Attention, vélo en mouvement.", CycleText.movingPhrase(velo, SpeechLanguage.Fr))
        assertEquals("Attention, bicycle moving.", CycleText.movingPhrase(velo, SpeechLanguage.En))
        assertEquals("Vélo arrêté, à midi.", CycleText.stoppedPhrase(velo, SpeechLanguage.Fr, 0.5f))
        assertEquals(
            "Bicycle stopped, at 12 o'clock.",
            CycleText.stoppedPhrase(velo, SpeechLanguage.En, 0.5f),
        )
        assertEquals("Trottinette arrêtée.", CycleText.stoppedPhrase(trottinette, SpeechLanguage.Fr))
    }

    /**
     * Ordre user du 01/11 : le mouvement d'un cycle ne dit plus son heure, donc
     * la phrase est fixe — et le pack voix la joue telle quelle. L'arrêt situé
     * (heure) reste variable : synthèse.
     */
    @Test
    fun `mouvement enregistre - arret situe en synthese`() {
        val velo = CycleConcepts.nameOf("bicycle")!!
        val trott = CycleConcepts.nameOf("trottinette")!!
        assertEquals("cycle_velo_moving", CycleText.clipIdFor("Attention, vélo en mouvement."))
        assertEquals("cycle_velo_moving", CycleText.clipIdFor("Attention, bicycle moving."))
        assertEquals("cycle_trott_moving", CycleText.clipIdFor("Attention, scooter moving."))
        assertEquals("cycle_velo_stopped", CycleText.clipIdFor("Vélo arrêté."))
        assertNull("arrêt situé : pas de clip", CycleText.clipIdFor("Vélo arrêté, à midi."))
        // « deux-roues » n'a pas encore de fichier dans le pack : la clé existe,
        // le clip manquant retombe en synthèse (comportement du pack : clip absent).
        assertEquals("cycle_deuxroues_moving", CycleText.clipIdFor("Attention, deux-roues en mouvement."))
        assertEquals("cycle_trott_moving", CycleText.movingClipId(trott))
        assertEquals("cycle_velo_stopped", CycleText.stoppedClipId(velo))
    }

    @Test
    fun `reset - oublie tout`() {
        val watcher = CycleWatcher()
        val boxes = listOf(bikeBox(430f), bikeBox(450f), bikeBox(470f))
        boxes.forEachIndexed { index, box ->
            watcher.update(listOf(det("bicycle", box)), listOf(reading("bicycle", 5f, box)), atMs = index * 100L)
        }
        watcher.reset()

        assertNull(watcher.lastAlertMs)
        assertNull(
            watcher.update(
                listOf(det("bicycle", bikeBox(490f))),
                listOf(reading("bicycle", 5f, bikeBox(490f))),
                atMs = 300L,
            ),
        )
    }
}
