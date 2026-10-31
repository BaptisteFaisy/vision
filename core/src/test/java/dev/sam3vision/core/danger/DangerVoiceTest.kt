package dev.sam3vision.core.danger

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceAnnouncer
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règle voix du danger (JUnit seul, 100 % `:core`) : pistes YOLO → sténopé
 * (personne 1,70 m) → labels placés → guidage EXISTANT. Une personne ≤ 3 m qui
 * s'approche par la droite fait dire « Gauche. » (contournement), un obstacle
 * au contact fait dire le stop TwinLite — aucune voix nouvelle, aucun seuil
 * nouveau, seule l'alimentation change.
 */
class DangerVoiceTest {

    /** Image YOLO côté sonde : 512 px (sous-échantillonnée, comme en direct). */
    private val width = 512
    private val height = 512

    /** Piste personne à [centerX], boîte haute de [boxHPx] px (id stable 7). */
    private fun track(centerX: Float, boxHPx: Float): DangerTrack {
        val w = 0.2f
        val h = boxHPx / height
        val y1 = 0.5f - h / 2f
        return DangerTrack(7, "personne", 0.8f, listOf(centerX - w / 2f, y1, centerX + w / 2f, y1 + h), 3, 0, 0L, 0L)
    }

    /** Piste → distance sténopé lissée (le même [DistanceMeter] que la sonde). */
    private fun metersOf(meter: DistanceMeter, track: DangerTrack): Float {
        val detections = listOf(track.toDetection(width, height))
        val readings = meter.measure(width, height, detections, listOf(track.id + DANGER_SMOOTHER_OFFSET))
        assertEquals(1, readings.size)
        return readings[0].meters
    }

    @Test
    fun `une personne qui s'approche par la droite fait dire gauche`() {
        val meter = DistanceMeter(LensSpec.Default)
        val voice = GuidanceAnnouncer()
        // 220 → 250 → 290 px de haut ≈ 2,99 → 2,63 → 2,27 m : deux baisses > 5 cm.
        val heights = listOf(220f, 250f, 290f)
        var phrase: String? = null
        heights.forEachIndexed { frame, hPx ->
            val track = track(0.7f, hPx)
            val meters = metersOf(meter, track)
            assertTrue("frame $frame à $meters m, attendu ≤ 3 m", meters <= 3f)
            val labels = dangerPlacedLabels(listOf(track), listOf(readingOf(track, meters)))
            phrase = voice.onFrame(frame * 200L, labels, SpeechLanguage.Fr)
        }
        assertEquals("Gauche.", phrase)
    }

    @Test
    fun `un obstacle au contact fait dire le contournement twinlite`() {
        val voice = GuidanceAnnouncer()
        val phrase = voice.onFrame(0L, listOf(PlacedLabel("personne", 0.5f, 0.5f, 0.5f)), SpeechLanguage.Fr)
        assertEquals("gauche.", phrase)
    }

    @Test
    fun `une personne lointaine ne declenche ni contournement ni stop`() {
        val voice = GuidanceAnnouncer()
        val phrase = voice.onFrame(0L, listOf(PlacedLabel("personne", 0.5f, 0.5f, 12f)), SpeechLanguage.Fr)
        assertEquals("Tout droit.", phrase)
    }

    @Test
    fun `seules les pistes mesurees parlent`() {
        val near = track(0.5f, 250f)
        val far = DangerTrack(9, "personne", 0.8f, near.box, 3, 0, 0L, 0L)
        val labels = dangerPlacedLabels(listOf(near, far), listOf(readingOf(near, 2.5f)))
        assertEquals(1, labels.size)
        assertEquals("personne", labels[0].label)
        assertEquals(2.5f, labels[0].meters!!, 1e-6f)
    }

    private fun readingOf(track: DangerTrack, meters: Float): DistanceReading =
        DistanceReading(track.id + DANGER_SMOOTHER_OFFSET, track.label, meters, meters, track.box)
}
