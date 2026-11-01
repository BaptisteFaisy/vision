package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrosswalkScanPhase
import dev.sam3vision.core.crossing.NoLightGate
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.TraverseVeto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traversée sans feu (ordre user du 31/10) : pas de « Regardez à gauche / à
 * droite » — la voiture en mouvement parle en premier (priorité absolue,
 * même loin), « Traversez. » quand aucun danger ne se voit, silence sinon.
 */
class NoLightCrossingTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun carReading(meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = "car", meters = meters, rawMeters = meters, box = box)

    private fun carBox(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    /** Pan qui suit la glissade des boîtes (+5 px/frame sur 640) : la caméra bouge, pas la voiture. */
    private fun panMotion(): FrameMotion =
        FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)

    private fun resetWatches() {
        CrossingVehicleWatch.reset()
        CrosswalkScanWatch.reset()
        NoLightCrossWatch.reset()
        CrossingLightDueWatch.reset()
    }

    private fun calm(language: SpeechLanguage, atMs: Long): String? =
        crossingPhraseOf(null, emptyList(), emptyList(), emptyList(), atMs, language, noLight = true)

    @Test
    fun `patch - 4 frames sans boite taisent le feu`() {
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val gate = NoLightGate()
        val frame = LiveFrame(100, 100, 0L, emptyList())
        val language = SpeechLanguage.Fr
        fun patch(atMs: Long) = crossingPatchOf(
            announcer, veto, true, frame, LensSpec.Default, "", atMs, language, noLightGate = gate,
        )

        patch(0L)
        // Avant confirmation : voix feu normale (rouge à la 2e frame).
        assertEquals("Rouge, attendez.", patch(200L)?.cue?.phrase)
        patch(400L)
        val confirmed = patch(600L)

        assertTrue(confirmed?.noLight == true)
        assertNull(confirmed?.cue)
        assertEquals(CrossingState.Red, confirmed?.state)
        assertNull(confirmed?.box)
    }

    @Test
    fun `sans feu - traversez des la 2e frame calme`() {
        resetWatches()

        assertNull(calm(SpeechLanguage.Fr, 0L))
        assertEquals("Traversez.", calm(SpeechLanguage.Fr, 200L))
    }

    @Test
    fun `sans feu - traversez en anglais`() {
        resetWatches()

        assertNull(calm(SpeechLanguage.En, 0L))
        assertEquals("Cross.", calm(SpeechLanguage.En, 200L))
    }

    @Test
    fun `sans feu - repetition toutes les 2 s`() {
        resetWatches()
        val language = SpeechLanguage.Fr

        assertNull(calm(language, 0L))
        assertEquals("Traversez.", calm(language, 200L))
        assertNull(calm(language, 400L))
        assertEquals("Traversez.", calm(language, 2_300L))
    }

    @Test
    fun `sans feu - voiture en mouvement prioritaire, scan muet`() {
        resetWatches()
        val language = SpeechLanguage.En
        fun moving(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(5f, frame)),
                atMs, language, scanTrigger = true, noLight = true,
            )
        }

        assertNull(moving(100f, 0L))
        assertNull(moving(110f, 100L))
        assertEquals("Stop, car moving.", moving(120f, 200L))
        assertEquals(CrosswalkScanPhase.Idle, CrosswalkScanWatch.phase())
    }

    @Test
    fun `sans feu - mouvement loin parle doux, jamais traversez`() {
        resetWatches()
        val language = SpeechLanguage.En
        fun far(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(12f, frame)),
                atMs, language, noLight = true,
            )
        }

        assertNull(far(100f, 0L))
        assertNull(far(110f, 100L))
        assertEquals("Car moving.", far(120f, 200L))
    }

    @Test
    fun `sans feu - voix vehicules douce devant droite et gauche`() {
        resetWatches()
        val language = SpeechLanguage.En
        fun far(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(12f, frame)),
                atMs, language, noLight = true, steering = "Droite.",
            )
        }

        assertEquals("Droite.", far(100f, 0L))
        assertEquals("Droite.", far(110f, 100L))
        assertEquals("Car moving.", far(120f, 200L))
    }

    @Test
    fun `sans feu - voiture au-dela de 15 m parle quand elle bouge`() {
        resetWatches()
        val language = SpeechLanguage.En
        fun far(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(30f, frame)),
                atMs, language, noLight = true,
            )
        }

        assertNull(far(100f, 0L))
        assertNull(far(110f, 100L))
        assertEquals("Car moving.", far(120f, 200L))
    }

    @Test
    fun `sans feu - transition feu, la voiture parle au lieu du verdict`() {
        resetWatches()
        val cue = CrossingCue("Rouge, attendez.", false, CrossingState.Red)
        assertEquals(
            "Rouge, attendez.",
            crossingPhraseOf(cue, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr),
        )
        // Le feu sort du champ, une voiture à 30 m roule : la voix
        // véhicules parle (devant le verdict), sans attendre.
        fun far(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(30f, frame)),
                atMs, SpeechLanguage.En, noLight = true,
            )
        }

        assertNull(far(100f, 100L))
        assertNull(far(110f, 200L))
        assertEquals("Car moving.", far(120f, 300L))
    }

    @Test
    fun `sans feu - cooldown mouvement dit attendez, jamais traversez`() {
        resetWatches()
        val language = SpeechLanguage.En
        fun moving(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(5f, frame)),
                atMs, language, noLight = true,
            )
        }

        moving(100f, 0L)
        moving(110f, 100L)
        assertEquals("Stop, car moving.", moving(120f, 200L))
        assertNull(moving(130f, 1_000L))
        // Toujours en mouvement 2 s après : « Wait. », pas « Cross. ».
        assertEquals("Wait.", moving(140f, 2_300L))
    }

    @Test
    fun `sans feu - voiture garee proche ne bloque pas traversez`() {
        resetWatches()
        fun parked(atMs: Long): String? {
            val frame = carBox(100f)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(5f, frame)),
                atMs, SpeechLanguage.Fr, noLight = true,
            )
        }

        assertNull(parked(0L))
        assertEquals("Traversez.", parked(200L))
        assertEquals("Voiture à l'arrêt, à 5 mètres.", parked(400L))
        assertNull(parked(600L))
        assertEquals("Traversez.", parked(2_500L))
    }

    @Test
    fun `sans feu - pan sur voiture garee lointaine dit traversez`() {
        resetWatches()
        fun panning(index: Int, atMs: Long): String? {
            val frame = carBox(100f, cx = 320f + index * 5f)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(30f, frame)),
                atMs, SpeechLanguage.Fr, noLight = true, motion = panMotion(),
            )
        }

        assertNull(panning(0, 0L))
        assertEquals("Traversez.", panning(1, 100L))
        assertNull(panning(2, 200L))
    }

    @Test
    fun `sans feu - voiture qui demarre contredit vite cross`() {
        resetWatches()
        fun moving(widthPx: Float, atMs: Long): String? {
            val frame = carBox(widthPx)
            return crossingPhraseOf(
                null, emptyList(), listOf(det("car", frame)), listOf(carReading(5f, frame)),
                atMs, SpeechLanguage.En, noLight = true,
            )
        }

        assertNull(calm(SpeechLanguage.En, 0L))
        assertEquals("Cross.", calm(SpeechLanguage.En, 200L))
        assertNull(moving(100f, 400L))
        assertNull(moving(110f, 500L))
        assertEquals("Stop, car moving.", moving(120f, 600L))
    }

    @Test
    fun `sans feu - le feu parle toujours en premier`() {
        resetWatches()
        val cue = CrossingCue("Rouge, attendez.", false, CrossingState.Red)

        assertEquals(
            "Rouge, attendez.",
            crossingPhraseOf(
                cue, emptyList(), emptyList(), emptyList(), 0L, SpeechLanguage.Fr, noLight = true,
            ),
        )
    }
}
