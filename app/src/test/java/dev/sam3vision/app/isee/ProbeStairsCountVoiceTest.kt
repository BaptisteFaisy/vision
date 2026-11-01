package dev.sam3vision.app.isee

import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voix du compteur de marches (SPEC v4, niveau 6) : quand SAM nomme
 * l'escalier et que la caméra voit des marches fraîches, le battement
 * 300 ms poste le compte (« N marches. »), une fois par compte puis répété
 * toutes les 0,5 s — devant le chat (6 < 9) ; sans escalier nommé, sans
 * compte frais ou en Traversée : silence.
 *
 * Jamais `advanceUntilIdle` ici : le battement est une boucle infinie, seul
 * `advanceTimeBy` avance sans boucler (miroir `ProbePulse`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeStairsCountVoiceTest {

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

    /** Profondeur factice : « stairs going up » à 3 mètres, boîte nette. */
    private class StairsDepth : DepthMeter {
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

    private fun textes(): List<String> = speaker.said.map { it.text }

    @Test
    fun `meme compte se repete toutes les 0,5 s`() = runTest {
        val (probe, fake, scope) = harness(this)
        try {
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            val offered = StairCannyCount.lineCount()
            assertTrue(offered > 0)

            respond(probe, fake, "A")
            assertTrue(stairsFoundOf(probe.state.value).detected)
            runCurrent()
            // Le battement 300 ms a déjà pu redire le compte pendant la
            // fenêtre de réponse : on verrouille la cadence, pas le nombre.
            val first = speaker.said.size
            assertTrue(first > 0)
            assertEquals(OutdoorLevel.STAIRS, speaker.said.first().level)
            assertTrue(textes().all { it == "$offered marches." })

            // 1,1 s puis 1,2 s, bornes hors temps de battement : 2
            // répétitions pile par fenêtre (le dit à +600/+1200 du
            // dernier, le suivant à +1800 hors fenêtre).
            advanceTimeBy(1_100L)
            runCurrent()
            assertEquals(first + 2, speaker.said.size)

            advanceTimeBy(1_200L)
            runCurrent()
            assertEquals(first + 4, speaker.said.size)
            assertTrue(textes().all { it == "$offered marches." })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sans escalier nomme silence`() = runTest {
        val (_, _, scope) = harness(this)
        try {
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            assertTrue(StairCannyCount.lineCount() > 0)

            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(speaker.said.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sans compte frais silence`() = runTest {
        val (probe, fake, scope) = harness(this)
        try {
            respond(probe, fake, "A")
            assertTrue(stairsFoundOf(probe.state.value).detected)

            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(speaker.said.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `compteur poste devant le chat en lecture`() = runTest {
        val (probe, fake, scope) = harness(this)
        try {
            VoiceBus.chatSpeaking(true)
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            val offered = StairCannyCount.lineCount()
            assertTrue(offered > 0)

            respond(probe, fake, "A")
            assertTrue(stairsFoundOf(probe.state.value).detected)
            runCurrent()

            // Le compte parle malgré le chat en lecture (la cadence, elle,
            // est verrouillée par le test de répétition).
            assertTrue(textes().isNotEmpty())
            assertTrue(textes().all { it == "$offered marches." })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `traversee coupe le compteur`() = runTest {
        val (probe, fake, scope) = harness(this)
        try {
            respond(probe, fake, "A")
            probe.setCrossingActive(Settings.Default, true)
            StairCannyCount.offerLuma(stairsLuma(50, 58, 66, 74, 82, 90))
            assertTrue(StairCannyCount.lineCount() > 0)

            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(speaker.said.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private data class Wiring(
        val probe: LiveProbe,
        val fake: ProbeTestClient,
        val scope: CoroutineScope,
    )

    /** Sonde sur faux client, compteur câblé (lui seul) vers le bus. */
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
        // Scheduler coupé : ce test verrouille le compteur, pas les keyframes (voir LiveAdaptiveTest).
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        val clock = { scope.testScheduler.currentTime }
        val watchers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        probe.observeStairsCount(watchers, { SpeechLanguage.Fr }, nowMs = clock)
        return Wiring(probe, fake, watchers)
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
}
