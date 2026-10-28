package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.ReleaseOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Caméra coupée = carte éteinte : `stop` ferme les deux sessions (normale + froide),
 * jette la réponse en vol et refuse les frames suivantes — puis éteint le conteneur
 * aussitôt (`POST /release`, coût 0 : sans lui, 60 s de réduintage facturées).
 * `configure` referme les anciennes sessions au lieu de les orpheliner (un socket
 * orphelin garderait le conteneur Modal allumé, facturé).
 */
class LiveProbeStopTest {

    private var savedRelease: (suspend (String) -> Result<ReleaseOutcome>)? = null

    /**
     * Isole le réseau : `stop` éteint la carte en tâche de fond, les tests
     * n'appellent jamais le backend (le faux est remplacé par test si besoin).
     */
    @Before
    fun stubRelease() {
        savedRelease = releaseOnStop
        releaseOnStop = { Result.success(ReleaseOutcome(released = false, code = 0, body = "stub")) }
    }

    @After
    fun restoreRelease() {
        releaseOnStop = savedRelease ?: releaseOnStop
    }

    @Test
    fun `stop ferme les sessions et coupe l_envoi`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.onFrame("A")
        advanceUntilIdle()
        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        assertEquals(1, probe.state.value.frames)

        probe.stop()

        assertFalse(probe.wantsFrame())
        probe.onFrame("B")
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        assertEquals(2, fake.closed)
        assertEquals(0, probe.state.value.frames)
    }

    @Test
    fun `stop jette la reponse en vol`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.onFrame("A")
        advanceUntilIdle()
        probe.stop()

        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()

        assertEquals(0, probe.state.value.frames)
    }

    @Test
    fun `configure referme les anciennes sessions`() = runTest {
        val (probe, fake) = probeHarness(this)

        probe.configure(PROBE_TEST_ENDPOINT)

        assertEquals(2, fake.closed)
        probe.onFrame("A")
        advanceUntilIdle()
        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        assertEquals(1, probe.state.value.frames)
    }

    /**
     * L'extinction part en tâche de fond sur une portée réelle : l'attente se fait
     * hors `runTest`, sinon le temps virtuel coupe le délai avant que le faux réponde.
     */
    @Test
    fun `stop eteint le conteneur une fois avec l endpoint`() {
        val probe = runTest { probeHarness(this).first }
        val calls = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        releaseOnStop = { endpoint ->
            calls += endpoint
            done.complete(Unit)
            Result.success(ReleaseOutcome(released = true, code = 200, body = "{}"))
        }

        probe.stop()
        runBlocking { withTimeout(10_000L) { done.await() } }

        assertEquals(listOf(PROBE_TEST_ENDPOINT), calls)
    }

    @Test
    fun `stop sans endpoint n eteint rien`() = runTest {
        val probe = LiveProbe(
            scope = this,
            clock = { 0L },
            clientFor = { _, _, _ -> ProbeTestClient(mutableListOf()) },
        )
        var calls = 0
        releaseOnStop = {
            calls += 1
            Result.success(ReleaseOutcome(released = true, code = 200, body = "{}"))
        }

        probe.stop()

        assertEquals(0, calls)
    }
}
