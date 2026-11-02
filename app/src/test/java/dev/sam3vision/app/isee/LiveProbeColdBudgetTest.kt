package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le budget froid (60 s) est payé **une fois par réveil** : la première frame aboutie
 * le solde, et un `configure()` en séance (réglage, bascule du mode Traversée) ne
 * repose plus 60 s de latence tolérée — ni ne redit « up to a minute ». Le lien, lui,
 * publie son âge et son silence dans l'état, que la notice et la voix peuvent lire.
 */
class LiveProbeColdBudgetTest {

    /** Horloge monotone pilotée (ns) : même base qu'`imageInfo.timestamp`. */
    private var ns = 0L

    private fun frame() = LiveFrame(width = 512, height = 384, elapsedMs = 120L, objects = emptyList())

    @Test
    fun `le budget froid tombe a la premiere frame et ne se rearme plus`() = runTest {
        val timeouts = mutableListOf<Long>()
        val fake = ProbeTestClient(mutableListOf())
        // Réveil négatif aussitôt : la cadence seule est testée, pas la carte.
        fake.healthGate = CompletableDeferred(
            Result.success(LiveHealth(ok = false, model = "sam3.1", device = "cuda", gpu = "A100")),
        )
        val probe = LiveProbe(
            scope = this,
            clock = { ns },
            background = StandardTestDispatcher(testScheduler),
            clientFor = { _, timeout, _ -> timeouts += timeout; fake },
        )
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        probe.configure(ENDPOINT)
        probe.begin()

        assertEquals("premier appel : budget froid", listOf(COLD_TIMEOUT_SECONDS), timeouts)
        assertEquals("la notice dit 60 s", COLD_TIMEOUT_SECONDS, probe.state.value.timeoutSec)
        assertTrue(probe.state.value.warming)

        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()

        assertEquals(1, probe.state.value.frames)
        assertEquals("le délai froid retombe", DEFAULT_TIMEOUT_SECONDS, probe.state.value.timeoutSec)
        assertFalse("plus de notice de réveil", probe.state.value.warming)
        assertEquals("le lien est chaud", ProbeLinkKind.Warm, probe.state.value.link.kind)

        probe.configure(ENDPOINT) // réglage / bascule de mode en séance

        assertEquals("aucun second budget froid", DEFAULT_TIMEOUT_SECONDS, timeouts.last())
        assertEquals("l'état ne redit plus 60 s", DEFAULT_TIMEOUT_SECONDS, probe.state.value.timeoutSec)
        assertFalse(probe.state.value.warming)
        assertSame("une seule session, froide et chaude (FIX-18)", probe.session, probe.coldSession)
    }

    @Test
    fun `le silence se publie dans l etat et se tait au retour`() = runTest {
        val (probe, _) = probeHarness(this)
        val t0 = System.currentTimeMillis()

        probe.noteAnswer(t0)
        assertEquals(ProbeLinkKind.Warm, probe.state.value.link.kind)
        assertNull(probe.state.value.error)

        probe.publishLink(t0 + LINK_SILENCE_MS + 1_200)

        val silent = probe.state.value
        assertEquals(ProbeLinkKind.Silent, silent.link.kind)
        assertEquals("âge publié", LINK_SILENCE_MS + 1_200, silent.link.lastAnswerAgeMs)
        assertTrue("le texte de silence est publié", silent.error!!.startsWith(SILENCE_PREFIX))
        val notice = liveProbeNotice(silent)!!
        assertTrue(notice.contains("no reply for"))
        assertFalse("ce n'est pas un réveil de GPU", notice.contains("GPU"))

        probe.noteAnswer(t0 + LINK_SILENCE_MS + 2_000)

        assertEquals(ProbeLinkKind.Warm, probe.state.value.link.kind)
        assertNull("le silence se tait au retour", probe.state.value.error)
    }

    @Test
    fun `un echec repete ne compte qu une ligne de log et arme les compteurs`() = runTest {
        val (probe, _) = probeHarness(this)
        probe.noteFailure(ApiError.Timeout())
        probe.noteFailure(ApiError.Timeout())

        val link = probe.state.value.link
        assertEquals("deux échecs consécutifs", 2, link.consecutiveFailures)
        assertEquals("aucune réponse encore : budget froid, pas de silence", ProbeLinkKind.Cold, link.kind)
        assertTrue(
            "sans réponse, l'échec parle encore du GPU",
            probe.state.value.error!!.contains("GPU waking up"),
        )
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
    }
}
