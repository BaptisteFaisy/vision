package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La voix de la sonde : un mot ré-émis par les frames suivantes n'annule
 * pas sa synthèse (seul un mot plus frais le fait), et le mot doux reste
 * muet quand le chat lit (seul « Stop. » coupe).
 */
class ProbeWiringSpokenTest {

    private var ns = 0L

    private fun frame(objects: List<SegObject> = emptyList(), elapsedMs: Long = 120L) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = objects)

    /** Personne mesurée au centre : « gauche. » (voix TwinLite) */
    private fun personne() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(206f, 400f, 306f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    /** Personne décalée à gauche du couloir : « droite. » (voix TwinLite) */
    private fun obstacleAGauche() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(180f, 400f, 230f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    /** Personne au contact, boîte coupée : non mesurée → « gauche. » (voix TwinLite) */
    private fun contact() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(156f, 0f, 356f, 1138f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    @Test
    fun `meme phrase reemise, une seule synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val completions = mutableListOf<String>()
        val gate = CompletableDeferred<Result<ByteArray>>()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
            synthesize = { _, _, text ->
                attempts += text
                gate.await()
                completions += text
                Result.success(ByteArray(0))
            },
        )
        val (probe, fake) = harness(this)
        avecVoix(probe, voice, { true }) {
            respond(personne(), fake, probe, "A", "B", "C")
            gate.complete(Result.success(ByteArray(0)))
            advanceUntilIdle()
        }

        assertEquals(listOf("left."), attempts)
        assertEquals(listOf("left."), completions)
    }

    @Test
    fun `phrase fraiche, dernier gagnant garde`() = runTest {
        val attempts = mutableListOf<String>()
        val completions = mutableListOf<String>()
        val gate = CompletableDeferred<Result<ByteArray>>()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
            synthesize = { _, _, text ->
                attempts += text
                gate.await()
                completions += text
                Result.success(ByteArray(0))
            },
        )
        val (probe, fake) = harness(this)
        avecVoix(probe, voice, { true }) {
            respond(personne(), fake, probe, "A")
            respond(obstacleAGauche(), fake, probe, "B", "C")
            gate.complete(Result.success(ByteArray(0)))
            advanceUntilIdle()
        }

        assertEquals(
            listOf("left.", "right."),
            attempts,
        )
        assertEquals(listOf("right."), completions)
    }

    @Test
    fun `mots du guidage partent en clip`() {
        assertEquals("guide_straight", spokenClipId("Straight."))
        assertEquals("guide_right", spokenClipId("Droite."))
        assertEquals("tl_left", spokenClipId("left."))
        assertNull(spokenClipId("Personne à 1 mètre."))
    }

    @Test
    fun `mot doux muet quand le chat lit`() = runTest {
        val spoken = mutableListOf<String>()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
            onSpeaking = { if (it.isNotEmpty()) spoken += it },
            synthesize = { _, _, text -> Result.success("$text-audio".toByteArray()) },
        )
        val (probe, fake) = harness(this)
        avecVoix(probe, voice, { false }) {
            respond(contact(), fake, probe, "A", "B")
            advanceUntilIdle()
        }

        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `phrase douce tuee puis rattrapee au silence`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
            onSpeaking = { if (it.isNotEmpty()) spoken += it },
            synthesize = { _, _, text ->
                attempts += text
                Result.success("$text-audio".toByteArray())
            },
        )
        val (probe, fake) = harness(this)
        avecVoix(probe, voice, { false }) {
            respond(personne(), fake, probe, "A", "B")
            advanceUntilIdle()
        }
        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())

        // Silence revenu : la file rejoue la phrase tuée, une seule fois.
        voice.flushPending(playerIdle = true)

        assertEquals(1, spoken.size)
        assertEquals(attempts, spoken)
    }

    /** Sonde guidée branchée sur le faux client partagé, prête à recevoir des frames. */
    private fun harness(scope: TestScope): Pair<LiveProbe, ProbeTestClient> {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = scope,
            clock = { ns },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
        )
        probe.configure(PROBE_TEST_ENDPOINT, guide = true)
        probe.begin()
        return probe to fake
    }

    /**
     * Câble la voix sur un scope au même ordonnanceur (temps virtuel partagé),
     * annulé en fin de test : le collecteur infini ne doit ni fuir ni pendre.
     */
    private fun TestScope.avecVoix(
        probe: LiveProbe,
        voice: ProximityVoice,
        playerIdle: () -> Boolean,
        corps: TestScope.() -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            probe.observeSpoken(scope, voice, playerIdle) { SpeechLanguage.Fr }
            corps()
        } finally {
            scope.cancel()
        }
    }

    /**
     * Livre [obj] sous chaque identifiant : une frame répondue par tour, sans
     * laisser le virtuel finir la synthèse en cours (c'est la course
     * frames lentes / synthèse lente qui faisait taire la voix).
     */
    private fun TestScope.respond(obj: SegObject, fake: ProbeTestClient, probe: LiveProbe, vararg ids: String) {
        ids.forEachIndexed { index, id ->
            probe.onFrame(id, captureAtNs = 8_000_000L + index * 6_000_000L)
            advanceTimeBy(1_000L)
            fake.respond(id, frame(listOf(obj)))
            advanceTimeBy(1_000L)
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("spokentest").toFile().apply { deleteOnExit() }
}
