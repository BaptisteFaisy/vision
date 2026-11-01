package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirmation profondeur du « en mouvement » (PISTE2) : la croissance seule
 * ne parle plus quand la proximité ne suit pas (pan/zoom) — le tau suivi et
 * la croissance instantanée sont tous deux invalidés. Miroir de
 * `VehicleStateTest` (même voiture 100→110→120 à 5 m).
 */
class VehicleStateDepthTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    /** Frame de la voiture à 5 m, mesurée à [closeness] (`null` = profondeur muette). */
    private fun frame(
        watcher: VehicleStateWatcher,
        widthPx: Float,
        atMs: Long,
        closeness: Float?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): VehicleStateCue? {
        val box = box(widthPx)
        return watcher.update(
            listOf(det("car", box)),
            listOf(reading("car", 5f, box)),
            atMs = atMs,
            language = language,
            depthByBox = closeness?.let { mapOf(box to it) },
        )
    }

    @Test
    fun `croissance a profondeur constante - silence, c est un pan ou un zoom`() {
        val watcher = VehicleStateWatcher()
        assertNull(frame(watcher, 100f, 0L, 0.5f))
        assertNull("croissance sans hausse de proximité : pas de candidat", frame(watcher, 110f, 100L, 0.5f))
        assertNull("tau suivi invalidé lui aussi", frame(watcher, 120f, 200L, 0.5f))
    }

    @Test
    fun `croissance a profondeur croissante - en mouvement, urgent au centre`() {
        val watcher = VehicleStateWatcher(confirmFrames = 1, movingCooldownMs = 0L)
        assertNull(frame(watcher, 100f, 0L, 0.4f))
        listOf(110f to 0.5f, 120f to 0.6f, 130f to 0.7f, 140f to 0.8f).forEachIndexed { index, (width, depth) ->
            val cue = frame(watcher, width, (index + 1) * 100L, depth, SpeechLanguage.Fr)
            assertEquals("Voiture.", cue!!.phrase)
            assertFalse("croissance seule, tau run < 5 : pas urgent", cue.urgent)
        }
        val sixth = frame(watcher, 150f, 500L, 0.9f, SpeechLanguage.Fr)

        assertEquals("Voiture.", sixth!!.phrase)
        assertEquals(5f, sixth.meters!!, 0.001f)
        assertTrue("tau run 5 + proche + centre : urgent", sixth.urgent)
    }

    @Test
    fun `sans profondeur - repli geometrie (non-regression)`() {
        val watcher = VehicleStateWatcher()
        assertNull(frame(watcher, 100f, 0L, null))
        assertNull(frame(watcher, 110f, 100L, null))
        val third = frame(watcher, 120f, 200L, null, SpeechLanguage.Fr)

        assertNotNull(third)
        assertEquals("Voiture.", third!!.phrase)
    }

    @Test
    fun `profondeur partielle - le doute ne tait pas`() {
        val watcher = VehicleStateWatcher(confirmFrames = 1, movingCooldownMs = 0L)
        assertNull(frame(watcher, 100f, 0L, null))
        assertNotNull("croissance sans première mesure : le doute parle", frame(watcher, 110f, 100L, 0.5f))
        assertNull("profondeur constante, tau run < 5", frame(watcher, 120f, 200L, 0.5f))
        assertNull(frame(watcher, 130f, 300L, 0.5f))
        assertNull(frame(watcher, 140f, 400L, 0.5f))
        val sixth = frame(watcher, 150f, 500L, 0.5f, SpeechLanguage.Fr)

        assertNotNull("tau sans première mesure : le doute parle", sixth)
    }
}
