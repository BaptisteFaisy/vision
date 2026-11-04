package dev.sam3vision.core.traverse

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vans garés (vidéo Samsung 20261104_101016 : 2 vans blancs + passage piéton
 * sans feu) : un van se traite comme une voiture (ordre user du 04/11) —
 * garé il s'annonce « en stationnement » et n'interdit pas la traversée,
 * en mouvement il dit « Car. » comme elle. Séquences synthétiques, miroir
 * de `VehicleStateTest` et `TraverseVetoTest` (focale 1000 px).
 */
class VehicleVanTest {

    private val focalPx = 1000f

    @Test
    fun `van gare - annonce stationnement comme une voiture`() {
        val watcher = VehicleStateWatcher()
        repeat(2) { index ->
            watcher.update(
                listOf(vehicleDet("van", vehicleBox(100f))),
                listOf(vehicleReading("van", 8f, vehicleBox(100f))),
                atMs = index * 100L,
            )
        }
        val third = watcher.update(
            listOf(vehicleDet("van", vehicleBox(100f))),
            listOf(vehicleReading("van", 8f, vehicleBox(100f))),
            atMs = 200L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Voiture en stationnement, à 8 mètres.", third!!.phrase)
        assertFalse(third.moving)
        assertNull(third.safetyPhrase)
    }

    @Test
    fun `van en mouvement - dit car comme une voiture`() {
        val watcher = VehicleStateWatcher()
        watcher.update(
            listOf(vehicleDet("van", vehicleBox(100f))),
            listOf(vehicleReading("van", 5f, vehicleBox(100f))),
            atMs = 0L,
        )
        watcher.update(
            listOf(vehicleDet("van", vehicleBox(110f))),
            listOf(vehicleReading("van", 5f, vehicleBox(110f))),
            atMs = 100L,
        )
        val third = watcher.update(
            listOf(vehicleDet("van", vehicleBox(120f))),
            listOf(vehicleReading("van", 5f, vehicleBox(120f))),
            atMs = 200L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Voiture.", third!!.phrase)
        assertEquals("Stop, voiture en mouvement.", third.safetyPhrase)
    }

    @Test
    fun `van gare - pas de veto des la 2e frame`() {
        val veto = TraverseVeto()
        val box = listOf(220f, 140f, 420f, 340f) // 200 px de haut → ~9,7 m au gabarit van
        val first = veto.update(false, listOf(vehicleDet("van", box)), focalPx, 0L)
        assertTrue("première frame : pas d'arrêt prouvé, bloque", first.veto)
        val second = veto.update(false, listOf(vehicleDet("van", box)), focalPx, 100L)
        assertFalse("van immobile ≤ 15 m : pas de veto", second.veto)
        assertEquals(TraversePhase.ATTENDRE, second.state)
    }
}
