package dev.sam3vision.app.isee

import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
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
import org.junit.Before
import org.junit.Test

/**
 * Annonce escaliers sans porte (SPEC v4) : l'escalier nommé par SAM parle
 * au bus niveau 6, sans porte GPS ni porte escaliers — escalier sans
 * lecture de distance (labels seuls : ni hauteur connue, ni pose, ni
 * ancre) : le cas du terrain du 31/10.
 *
 * Jamais `advanceUntilIdle` ici : le compteur est une boucle infinie, seuls
 * `advanceTimeBy` + `runCurrent` avancent (miroir `ProbeStairsCountVoiceTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeStairsGateTest {

    /** Porte-parole faux : dits enregistrés (niveau, texte). */
    private class FakeBusSpeaker : VoiceBusSpeaker {
        data class Said(val level: Int, val text: String)

        val said = mutableListOf<Said>()
        override fun idle(): Boolean = true
        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += Said(level, text)
        }

        override fun cut() = Unit
    }

    private val speaker = FakeBusSpeaker()

    /** Sténopé seul : l'escalier nommé par SAM n'a aucune lecture de distance. */
    private class PinholeOnly : DepthMeter {
        override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>) = pinhole
        override fun reset() {}
    }

    @Before
    fun setUp() {
        StairCannyCount.clear()
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun tearDown() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
        StairCannyCount.clear()
    }

    @Test
    fun `escalier nomme poste niveau 6 sans porte`() = runTest {
        val (probe, fake, scope) = harness(this)
        try {
            respond(probe, fake, "A", "B")
            advanceTimeBy(2_000L)
            runCurrent()

            val annonce = speaker.said.filter { it.text == "Escalier devant." }
            assertEquals(1, annonce.size)
            assertEquals(OutdoorLevel.STAIRS, annonce.single().level)
        } finally {
            scope.cancel()
        }
    }

    private data class Wiring(
        val probe: LiveProbe,
        val fake: ProbeTestClient,
        val scope: CoroutineScope,
    )

    /** Les sept guetteurs sur faux client, branchés sur le bus. */
    private fun harness(scope: TestScope): Wiring {
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
        val watchers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        probe.observeHazards(watchers, { SpeechLanguage.Fr })
        return Wiring(probe, fake, watchers)
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
}
