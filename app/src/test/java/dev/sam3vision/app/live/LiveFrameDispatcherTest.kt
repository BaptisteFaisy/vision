package dev.sam3vision.app.live

import dev.sam3vision.core.live.FramePipeline
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveStats
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envois du Direct : la session `/live` part en cadence continue avec une frame de
 * réserve renvoyée **sans attendre** à la réponse ; le tracker garde sa cadence
 * adaptative. Une séance close entre l'envoi et la réponse ne draine rien.
 */
class LiveFrameDispatcherTest {

    private val frame = LiveFrame(100, 100, 10L, emptyList())

    private fun immediateClient(sent: MutableList<String>) = object : LiveClient {
        override suspend fun live(jpegB64: String, p: List<String>?, w: Int, m: Int) =
            run { sent += jpegB64; Result.success(frame) }

        override suspend fun health(): Result<LiveHealth> = error("inutilisé")
    }

    /** Pipeline tracker minimal : créneau adaptatif fixe, sans réserve. */
    private class FakeTracker(private val intervalMs: Long) : FramePipeline {
        var busy = false
        override val inFlight: Boolean get() = busy
        override fun intervalMsAdaptive(): Long = intervalMs
        override val generation: Long = 0L
        override fun isCurrent(era: Long): Boolean = era == 0L
        override val stats: LiveStats = LiveStats()
        override fun reset() {}
    }

    private class Probe {
        val dispatched = mutableListOf<Pair<String, Long>>()
        val results = mutableListOf<Triple<FramePipeline, Long, Result<LiveFrame>?>>()
    }

    private fun dispatcher(
        session: () -> LiveSession?,
        sent: MutableList<String>,
        probe: Probe,
        now: () -> Long,
        testScope: kotlinx.coroutines.test.TestScope,
    ) = LiveFrameDispatcher(
        scope = testScope,
        worker = UnconfinedTestDispatcher(testScope.testScheduler),
        session = session,
        prompts = { listOf("person") },
        onDispatch = { jpeg, at -> probe.dispatched += jpeg to at },
        onResult = { active, era, result -> probe.results += Triple(active, era, result) },
        clock = now,
    )

    @Test
    fun `sans pipeline rien ne part`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        val dispatcher = dispatcher({ null }, sent, probe, { 1_000L }, this)

        assertFalse(dispatcher.onFrame(null, "jpeg", 7L, 1_000L))
        assertTrue(sent.isEmpty())
        assertTrue(probe.dispatched.isEmpty())
    }

    @Test
    fun `jpeg vide refuse`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        val active = LiveSession(immediateClient(sent), clock = { 0L })
        val dispatcher = dispatcher({ active }, sent, probe, { 1_000L }, this)

        assertFalse(dispatcher.onFrame(active, "  ", 7L, 1_000L))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `live la reserve part sans attendre a la reponse`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val blocking = object : LiveClient {
            override suspend fun live(jpegB64: String, p: List<String>?, w: Int, m: Int) =
                run { gate.await(); sent += jpegB64; Result.success(frame) }

            override suspend fun health(): Result<LiveHealth> = error("inutilisé")
        }
        var now = 1_000L
        val probe = Probe()
        val active = LiveSession(blocking, clock = { 0L })
        val dispatcher = dispatcher({ active }, sent, probe, { now }, this)

        assertTrue(dispatcher.onFrame(active, "f1", 1L, now))
        advanceUntilIdle()
        assertTrue("la requête doit être en vol", active.inFlight)
        assertFalse("requête en vol : en réserve, pas d'envoi", dispatcher.onFrame(active, "f2", 2L, now))

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("la réserve part dès la réponse, sans attendre l'échéance", listOf("f1", "f2"), sent)
        assertEquals(2, probe.results.size)
        assertEquals(listOf("f1" to 1L, "f2" to 2L), probe.dispatched)
    }

    @Test
    fun `live cadence continue entre deux envois directs`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        var now = 1_000L
        val active = LiveSession(immediateClient(sent), clock = { 0L })
        val dispatcher = dispatcher({ active }, sent, probe, { now }, this)

        assertTrue(dispatcher.onFrame(active, "f1", 1L, now))
        advanceUntilIdle()
        // Sans mesure de latence (horloge figée), la continue retombe sur la cible 500 ms.
        now = 1_000L + active.intervalMsContinuous() - 1
        assertFalse("avant l'échéance : jetée", dispatcher.onFrame(active, "f2", 2L, now))
        now = 1_000L + active.intervalMsContinuous()
        assertTrue("à l'échéance : admise", dispatcher.onFrame(active, "f2", 2L, now))
        advanceUntilIdle()
        assertEquals(listOf("f1", "f2"), sent)
    }

    @Test
    fun `tracker cadence adaptative inchangee et sans reserve`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        val tracker = FakeTracker(intervalMs = 400L)
        val dispatcher = dispatcher({ null }, sent, probe, { 1_000L }, this)

        assertTrue(dispatcher.onFrame(tracker, "t1", 1L, 1_000L))
        advanceUntilIdle()
        assertFalse("le tracker n'a pas de réserve : trop tôt = jetée", dispatcher.onFrame(tracker, "t2", 2L, 1_399L))
        assertTrue(dispatcher.onFrame(tracker, "t2", 2L, 1_400L))
        advanceUntilIdle()
        // Le dispatcher ne sait envoyer qu'au `/live` : le tracker ne passe pas par lui.
        assertTrue("aucun envoi direct, le tracker a son propre transport", sent.isEmpty())
        assertEquals(2, probe.dispatched.size)
    }

    @Test
    fun `seance close entre l envoi et la reponse ne draine rien`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val blocking = object : LiveClient {
            override suspend fun live(jpegB64: String, p: List<String>?, w: Int, m: Int) =
                run { gate.await(); sent += jpegB64; Result.success(frame) }

            override suspend fun health(): Result<LiveHealth> = error("inutilisé")
        }
        var live: LiveSession? = null
        val probe = Probe()
        val active = LiveSession(blocking, clock = { 0L })
        live = active
        val dispatcher = dispatcher({ live }, sent, probe, { 1_000L }, this)

        assertTrue(dispatcher.onFrame(active, "f1", 1L, 1_000L))
        advanceUntilIdle()
        assertFalse(dispatcher.onFrame(active, "f2", 2L, 1_000L))

        active.reset()
        live = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("réponse périmée : la réserve ne part pas", listOf("f1"), sent)
        assertEquals("l'écran reçoit quand même la réponse (c'est lui qui filtre)", 1, probe.results.size)
        assertFalse(active.isCurrent(probe.results.single().second))
    }

    @Test
    fun `reset rend le creneau immediat et vide la reserve`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        var now = 1_000L
        val active = LiveSession(immediateClient(sent), clock = { 0L })
        val dispatcher = dispatcher({ active }, sent, probe, { now }, this)

        assertTrue(dispatcher.onFrame(active, "f1", 1L, now))
        advanceUntilIdle()
        assertFalse(dispatcher.onFrame(active, "f2", 2L, now))
        dispatcher.reset()
        assertTrue("nouvelle séance : immédiat", dispatcher.onFrame(active, "f2", 2L, now))
        advanceUntilIdle()
        assertEquals(listOf("f1", "f2"), sent)
    }

    @Test
    fun `wantsFrame live suit la pompe sans effet de bord`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        val now = 1_000L
        val active = LiveSession(immediateClient(sent), clock = { 0L })
        val dispatcher = dispatcher({ active }, sent, probe, { now }, this)

        assertTrue(dispatcher.wantsFrame(active, now))
        assertTrue("lecture seule : l'envoi passe encore", dispatcher.onFrame(active, "f1", 1L, now))
        advanceUntilIdle()
        assertFalse("échéance posée : la caméra économise l'encodage", dispatcher.wantsFrame(active, now))
        assertFalse(dispatcher.wantsFrame(null, now))
    }

    @Test
    fun `wantsFrame tracker suit la cadence sans l avancer`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        val tracker = FakeTracker(intervalMs = 400L)
        val dispatcher = dispatcher({ null }, sent, probe, { 1_000L }, this)

        assertTrue(dispatcher.wantsFrame(tracker, 1_000L))
        assertTrue(dispatcher.onFrame(tracker, "t1", 1L, 1_000L))
        advanceUntilIdle()
        assertFalse(dispatcher.wantsFrame(tracker, 1_399L))
        assertTrue(dispatcher.wantsFrame(tracker, 1_400L))
    }

    @Test
    fun `reset pose le bras de cadence tracker`() = runTest {
        val sent = mutableListOf<String>()
        val probe = Probe()
        val dispatcher = dispatcher({ null }, sent, probe, { 1_000L }, this)

        assertFalse("défaut : adaptatif", dispatcher.continuousCadence)
        dispatcher.reset(continuous = true)
        assertTrue(dispatcher.continuousCadence)
        dispatcher.reset()
        assertFalse(dispatcher.continuousCadence)
    }
}
