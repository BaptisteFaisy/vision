package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import java.util.Locale
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
 * Porte escaliers hors sourdine GPS : GPS coupé (ambiance fermée) mais chat
 * libre — l'escalier nommé par SAM parle quand même (la sécurité passe avant
 * le silence, contrat `IseeVoiceGate`), et se tait si sa propre porte est
 * fermée. Escalier sans lecture de distance (labels seuls : ni hauteur connue,
 * ni pose, ni ancre) : le cas du terrain du 31/10.
 *
 * Jamais `advanceUntilIdle` ici : le compteur est une boucle infinie, seuls
 * `advanceTimeBy` + `runCurrent` avancent (miroir `ProbeStairsCountVoiceTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeStairsGateTest {

    /** Pilote TTS système muet : la sécurité ne parle pas dans ce test. */
    private class SilentDriver : TtsDriver {
        override val speaking: Boolean get() = false
        override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean = true
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    /** Sténopé seul : l'escalier nommé par SAM n'a aucune lecture de distance. */
    private class PinholeOnly : DepthMeter {
        override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>) = pinhole
        override fun reset() {}
    }

    @Before
    fun setUp() = StairCannyCount.clear()

    @After
    fun tearDown() = StairCannyCount.clear()

    @Test
    fun `gps coupe mais chat libre, escalier annonce`() = runTest {
        val (probe, fake, spoken, scope) = harness(this, playerIdle = false, stairsAudible = true)
        try {
            respond(probe, fake, "A", "B")
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(1, spoken.count { it == "Escalier devant." })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `porte escaliers fermee, silence meme ambiance ouverte`() = runTest {
        val (probe, fake, spoken, scope) = harness(this, playerIdle = true, stairsAudible = false)
        try {
            respond(probe, fake, "A", "B")
            advanceTimeBy(2_000L)
            runCurrent()

            assertTrue(spoken.none { it.contains("Escalier") })
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

    /** Les huit guetteurs sur faux client, voix capturée, portes imposées. */
    private fun harness(scope: TestScope, playerIdle: Boolean, stairsAudible: Boolean): Wiring {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
            depthMeter = PinholeOnly(),
        )
        probe.configure(PROBE_TEST_ENDPOINT)
        probe.begin()
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        val spoken = mutableListOf<String>()
        val safety = SafetyVoice(
            player = SpeechPlayer(File(tempDir(), "securite")),
            system = SystemTtsEngine(SilentDriver()),
            settings = { Settings.Default },
            stopCurrent = {},
        )
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
            safety = safety,
            onSpeaking = { spoken += it },
            synthesize = { _, _, text -> Result.success("$text-audio".toByteArray()) },
        )
        val watchers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        probe.observeHazards(watchers, voice, { playerIdle }, { SpeechLanguage.Fr }, safety, { stairsAudible })
        return Wiring(probe, fake, spoken, watchers)
    }

    /** Escalier nommé sans mesure : libellé seul, boîte quelconque, pas de masque. */
    private fun stairs() = SegObject(
        label = "stairs",
        score = 0.9f,
        box = listOf(100f, 200f, 400f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

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
        Files.createTempDirectory("stairsgatetest").toFile().apply { deleteOnExit() }
}
