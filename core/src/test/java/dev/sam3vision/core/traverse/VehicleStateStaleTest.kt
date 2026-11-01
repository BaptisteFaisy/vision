package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pistes mortes (objet sorti, coupe de scène) : le veto garde le maintien
 * stale (fail-safe, 1,5 s), mais la voix exige une piste fraîche
 * ([ApproachVerdict.tauFresh]/[ApproachVerdict.lateralFresh]) — un tau stale
 * ne fait plus annoncer la garée présente (queues de taxi, clip3).
 * Séquences synthétiques.
 */
class VehicleStateStaleTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `tau stale - veto maintenu mais non frais, purge ensuite`() {
        val approach = VehicleApproach()
        listOf(100f, 110f, 120f, 130f, 140f).forEachIndexed { index, width ->
            approach.update(listOf(det("car", box(width))), atMs = index * 100L)
        }
        val armed = approach.update(listOf(det("car", box(150f))), atMs = 500L)
        assertTrue("run-5 : tau armé", armed.approaching)
        assertTrue("piste nourrie : frais", armed.tauFresh)

        val stale = approach.update(listOf(det("car", listOf(10f, 10f, 110f, 70f))), atMs = 600L)
        assertTrue("piste morte < 1,5 s : le veto tient (fail-safe)", stale.approaching)
        assertFalse("piste non nourrie : non frais", stale.tauFresh)

        val purged = approach.update(listOf(det("car", listOf(10f, 10f, 110f, 70f))), atMs = 2200L)
        assertFalse("piste purgée : silence", purged.approaching)
    }

    @Test
    fun `lateral stale - veto maintenu mais non frais`() {
        val approach = VehicleApproach()
        approach.update(listOf(det("car", box(100f, cx = 60f))), atMs = 0L)
        approach.update(listOf(det("car", box(100f, cx = 100f))), atMs = 100L)
        val moving = approach.update(listOf(det("car", box(100f, cx = 140f))), atMs = 200L)
        assertTrue("glissade : latéral", moving.lateralMoving)
        assertTrue("piste nourrie : frais", moving.lateralFresh)

        val stale = approach.update(listOf(det("car", listOf(500f, 300f, 600f, 360f))), atMs = 300L)
        assertTrue("piste morte : le veto tient", stale.lateralMoving)
        assertFalse("piste non nourrie : non frais", stale.lateralFresh)
    }

    @Test
    fun `voix stale - la garee presente ne capte pas le tau mort (coupe de scene)`() {
        // Phase 1 : approche franche (tau armé, annonce légitime). Phase 2
        // (coupe) : l'approchante a disparu, une garée (même label) est là —
        // sans fraîcheur, la garée capte le tau stale et annonce « Stop ».
        val watcher = VehicleStateWatcher()
        val reads = { width: Float -> listOf(reading("car", 5f, box(width))) }
        listOf(100f, 110f, 120f, 130f, 140f, 150f).forEachIndexed { index, width ->
            watcher.update(listOf(det("car", box(width))), reads(width), atMs = index * 100L, language = SpeechLanguage.En)
        }
        assertTrue("approche : brut vrai", watcher.movingThisFrame)

        val parkedBox = box(100f, cx = 100f)
        val parkedReads = listOf(reading("car", 5f, parkedBox))
        assertNull(watcher.update(listOf(det("car", parkedBox)), parkedReads, atMs = 600L, language = SpeechLanguage.En))
        assertFalse("tau mort : la garée ne dit pas mouvement", watcher.movingThisFrame)
        assertNull(watcher.update(listOf(det("car", parkedBox)), parkedReads, atMs = 700L, language = SpeechLanguage.En))
        assertFalse("tau mort : toujours pas", watcher.movingThisFrame)
        val calm = watcher.update(listOf(det("car", parkedBox)), parkedReads, atMs = 800L, language = SpeechLanguage.En)
        assertEquals("Car stopped, 5 meters away.", calm!!.phrase)
        assertFalse(calm.moving)
        assertNull("jamais de stop sur une garée", calm.safetyPhrase)
    }
}
