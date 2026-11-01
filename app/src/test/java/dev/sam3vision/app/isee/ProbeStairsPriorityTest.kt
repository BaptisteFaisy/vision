package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Priorité du compteur de marches : il parle toutes les secondes devant les
 * positions et le guidage normal — mais l'évitement (décalage humain,
 * couloir bouché, stop) parle toujours, même sur les escaliers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeStairsPriorityTest {

    @Before
    fun setUp() = StairCannyCount.clear()

    @After
    fun tearDown() = StairCannyCount.clear()

    private fun personne(x: Float, meters: Float?) = PlacedLabel("personne", x, 0.5f, meters)

    private fun stairs() = SegObject(
        label = "stairs going up",
        score = 0.9f,
        box = listOf(100f, 200f, 400f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    private fun stairsReading() =
        DistanceReading(-1, "stairs going up", 3f, 3f, listOf(100f, 200f, 400f, 900f))

    private fun stairsFrame() =
        LiveFrame(width = 512, height = 1138, elapsedMs = 120L, objects = listOf(stairs()))

    /** Plan 256×103 : fond sombre, une ligne blanche par marche. */
    private fun stairsLuma(): ByteArray {
        val luma = ByteArray(LumaPlane.SIZE) { 20 }
        for (y in listOf(50, 58, 66, 74, 82, 90)) {
            for (x in 0 until LumaPlane.WIDTH) luma[y * LumaPlane.WIDTH + x] = 255.toByte()
        }
        return luma
    }

    private fun probe(scope: TestScope, guide: Boolean = false, announce: Boolean = false): LiveProbe {
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
        )
        probe.voice.guide = guide
        probe.voice.announce = announce
        probe.voice.language = SpeechLanguage.Fr
        return probe
    }

    @Test
    fun `compteur actif = escalier nomme et marches fraiches`() {
        val reading = stairsReading()
        val obj = stairs()
        val humain = DistanceReading(-1, "personne", 2f, 2f, listOf(100f, 200f, 400f, 900f))

        assertFalse(stairsCounterActive(listOf(reading), listOf(obj), 0))
        assertTrue(stairsCounterActive(listOf(reading), listOf(obj), 5))
        assertTrue(stairsCounterActive(emptyList(), listOf(obj), 5))
        assertTrue(stairsCounterActive(listOf(reading), emptyList(), 5))
        assertFalse(stairsCounterActive(listOf(humain), emptyList(), 5))
    }

    @Test
    fun `sans esquive le compteur ne cede rien`() = runTest {
        assertFalse(probe(this).stairsYieldToAvoidance())
    }

    @Test
    fun `esquive humaine tait les escaliers`() = runTest {
        // Hors couloir (pas bouché) mais dans la fenêtre d'esquive (2 m de l'axe).
        val probe = probe(this, guide = true)
        probe.voice.phrase(0L, listOf(personne(0.78f, 3.0f)), SteeringCap.Straight)
        probe.voice.phrase(250L, listOf(personne(0.78f, 2.6f)), SteeringCap.Straight)
        val third = probe.voice.phrase(500L, listOf(personne(0.78f, 2.2f)), SteeringCap.Straight)

        assertTrue(probe.stairsYieldToAvoidance())
        assertNotNull(third)
        assertTrue(preemptsCrossing(third!!))
    }

    @Test
    fun `couloir bouche tait les escaliers`() = runTest {
        val probe = probe(this, guide = true)
        probe.voice.phrase(0L, listOf(personne(0.5f, 1.0f)), SteeringCap.Straight)

        assertTrue(probe.stairsYieldToAvoidance())
    }

    @Test
    fun `positions tues devant le compteur`() = runTest {
        val probe = probe(this, announce = true)
        StairCannyCount.offerLuma(stairsLuma())
        assertTrue(StairCannyCount.lineCount() > 0)

        val phrase = probe.phraseOf(
            null, listOf(personne(0.3f, 2.0f)), listOf(stairsReading()),
            emptyList(), stairsFrame(), 0L, SpeechLanguage.Fr, LensSpec.DEFAULT_H_FOV_DEG,
        )

        assertNull(phrase)
    }

    @Test
    fun `positions parlent sans compteur`() = runTest {
        val probe = probe(this, announce = true)

        val phrase = probe.phraseOf(
            null, listOf(personne(0.3f, 2.0f)), listOf(stairsReading()),
            emptyList(), stairsFrame(), 0L, SpeechLanguage.Fr, LensSpec.DEFAULT_H_FOV_DEG,
        )

        assertNotNull(phrase)
    }

    @Test
    fun `evitement parle sur les escaliers, cap normal tu`() = runTest {
        val probe = probe(this, guide = true)
        StairCannyCount.offerLuma(stairsLuma())
        assertTrue(StairCannyCount.lineCount() > 0)
        val readings = listOf(stairsReading())
        val frame = stairsFrame()

        val fov = LensSpec.DEFAULT_H_FOV_DEG
        assertNull(probe.phraseOf(null, emptyList(), readings, emptyList(), frame, 0L, SpeechLanguage.Fr, fov))

        probe.phraseOf(null, listOf(personne(0.78f, 3.0f)), readings, emptyList(), frame, 5_000L, SpeechLanguage.Fr, fov)
        probe.phraseOf(null, listOf(personne(0.78f, 2.6f)), readings, emptyList(), frame, 5_250L, SpeechLanguage.Fr, fov)
        val third = probe.phraseOf(
            null, listOf(personne(0.78f, 2.2f)), readings, emptyList(), frame, 5_500L, SpeechLanguage.Fr, fov,
        )

        assertNotNull(third)
        assertTrue(preemptsCrossing(third!!))
    }
}
