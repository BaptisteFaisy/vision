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
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voix du compteur de marches : quand SAM nomme l'escalier et que la caméra
 * voit des marches fraîches, le battement 300 ms dit le compte (« N
 * marches. »), une fois par compte puis répété toutes les 4 s ; sans
 * escalier nommé, sans compte frais ou en Traversée : silence.
 *
 * Jamais `advanceUntilIdle` ici : le battement est une boucle infinie, seul
 * `advanceTimeBy` avance sans boucler (miroir `ProbePulse`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeStairsCountVoiceTest {

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
    fun `escalier nomme et marches vues dit le compte une fois`() = runTest {
        val (probe, fake, spoken, scope) = harness(this)
        try {
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            val offered = StairCannyCount.lineCount()
            assertTrue(offered > 0)

            respond(probe, fake, "A")
            assertTrue(stairsFoundOf(probe.state.value).detected)
            advanceTimeBy(1_500L)
            runCurrent()

            assertEquals(listOf("$offered marches."), spoken)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sans escalier nomme silence`() = runTest {
        val (probe, fake, spoken, scope) = harness(this)
        try {
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            assertTrue(StairCannyCount.lineCount() > 0)

            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(spoken.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sans compte frais silence`() = runTest {
        val (probe, fake, spoken, scope) = harness(this)
        try {
            respond(probe, fake, "A")
            assertTrue(stairsFoundOf(probe.state.value).detected)

            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(spoken.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `traversee coupe le compteur`() = runTest {
        val (probe, fake, spoken, scope) = harness(this)
        try {
            respond(probe, fake, "A")
            probe.setCrossingActive(Settings.Default, true)
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            assertTrue(StairCannyCount.lineCount() > 0)

            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(spoken.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private data class Wiring(
        val probe: LiveProbe,
        val fake: ProbeTestClient,
        val spoken: MutableList<String>,
        val scope: CoroutineScope,
    )

    /** Sonde sur faux client + voix capturée, compteur câblé (lui seul). */
    private fun harness(scope: TestScope): Wiring {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
            depthMeter = StairsDepth(),
        )
        probe.configure(PROBE_TEST_ENDPOINT)
        probe.begin()
        val spoken = mutableListOf<String>()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
            safety = testSafetyVoice(),
            onSpeaking = { if (it.isNotEmpty()) spoken += it },
            synthesize = { _, _, text -> Result.success("$text-audio".toByteArray()) },
        )
        val watchers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        probe.observeStairsCount(watchers, voice, { true }, { SpeechLanguage.Fr })
        return Wiring(probe, fake, spoken, watchers)
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
        Files.createTempDirectory("stairscountvoicetest").toFile().apply { deleteOnExit() }
}
