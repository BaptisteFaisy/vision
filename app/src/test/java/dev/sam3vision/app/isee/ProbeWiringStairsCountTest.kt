package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairWatcher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voix escaliers branchée sur le compteur caméra : quand SAM nomme l'escalier
 * et que la frame voit des marches, le guetteur compte (le compte à rebours
 * « Plus que N marches. » devient possible) ; sans compte frais, l'annonce
 * reste standard (non compté, pas « zéro marche »).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeWiringStairsCountTest {

    @Before
    fun setUp() = StairCannyCount.clear()

    @After
    fun tearDown() = StairCannyCount.clear()

    /** Profondeur factice : « stairs going up » à 3 mètres, boîte nette. */
    private class StairsDepth : DepthMeter {
        override fun offer(jpegB64: String) {}
        override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>) =
            pinhole + DistanceReading(-1, "stairs going up", 3f, 3f, listOf(100f, 200f, 400f, 900f))

        override fun reset() {}
    }

    /** Sténopé seul : l'escalier nommé par SAM n'a aucune lecture de distance. */
    private class PinholeOnly : DepthMeter {
        override fun offer(jpegB64: String) {}
        override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>) = pinhole
        override fun reset() {}
    }

    private fun stairs() = SegObject(
        label = "stairs going up",
        score = 0.9f,
        box = listOf(100f, 200f, 400f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    /** Plan 256×103 : fond sombre, une ligne blanche par marche, dans la ROI. */
    private fun stairsLuma(vararg rows: Int): ByteArray {
        val luma = ByteArray(LumaPlane.SIZE) { 20 }
        for (y in rows) {
            for (x in 0 until LumaPlane.WIDTH) luma[y * LumaPlane.WIDTH + x] = 255.toByte()
        }
        return luma
    }

    @Test
    fun `compte frais de la camera nourrit le guetteur`() = runTest {
        val (probe, fake, watcher, scope) = harness(this)
        try {
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            val offered = StairCannyCount.lineCount()
            assertTrue(offered > 0)

            respond(probe, fake, "A", "B")
            advanceUntilIdle()

            assertEquals(offered, watcher.smoothedLineCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `labels seuls sans lecture comptent quand meme`() = runTest {
        val (probe, fake, watcher, scope) = labelsOnlyHarness(this)
        try {
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            val offered = StairCannyCount.lineCount()
            assertTrue(offered > 0)

            respond(probe, fake, "A", "B")
            advanceUntilIdle()

            assertEquals(offered, watcher.smoothedLineCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sans compte frais, annonce non comptee`() = runTest {
        val (probe, fake, watcher, scope) = harness(this)
        try {
            respond(probe, fake, "A", "B")
            advanceUntilIdle()

            assertNull(watcher.smoothedLineCount)
        } finally {
            scope.cancel()
        }
    }

    private data class Wiring(
        val probe: LiveProbe,
        val fake: ProbeTestClient,
        val watcher: StairWatcher,
        val scope: CoroutineScope,
    )

    /** Sonde sur faux client + profondeur qui nomme l'escalier, guetteur câblé. */
    private fun harness(scope: TestScope): Wiring = meterHarness(scope, StairsDepth())

    /** Même sonde, sténopé seul : labels SAM sans aucune lecture escalier. */
    private fun labelsOnlyHarness(scope: TestScope): Wiring = meterHarness(scope, PinholeOnly())

    private fun meterHarness(scope: TestScope, meter: DepthMeter): Wiring {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
            depthMeter = meter,
        )
        probe.configure(PROBE_TEST_ENDPOINT)
        probe.begin()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default },
        )
        val watchers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        val watcher = probe.observeStairs(watchers, voice, { true }) { SpeechLanguage.Fr }
        return Wiring(probe, fake, watcher, watchers)
    }

    /** Livre une frame d'escalier par identifiant, comme le ferait le réseau. */
    private fun TestScope.respond(probe: LiveProbe, fake: ProbeTestClient, vararg ids: String) {
        ids.forEachIndexed { index, id ->
            probe.onFrame(id, captureAtNs = 8_000_000L + index * 6_000_000L)
            advanceTimeBy(1_000L)
            fake.respond(id, LiveFrame(width = 512, height = 1138, elapsedMs = 120L, objects = listOf(stairs())))
            advanceTimeBy(1_000L)
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("stairscounttest").toFile().apply { deleteOnExit() }
}
