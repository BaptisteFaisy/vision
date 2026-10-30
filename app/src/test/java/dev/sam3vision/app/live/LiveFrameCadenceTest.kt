package dev.sam3vision.app.live

import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadence du direct : une frame en vol maximum, un créneau adaptatif. Une frame proposée
 * trop tôt est **jetée** (jamais mise en attente) — c'est ce qui protège le lien montant.
 */
class LiveFrameCadenceTest {

    private val client = object : LiveClient {
        override suspend fun live(jpegB64: String, prompts: List<String>?, workEdge: Int, maxObjects: Int) =
            Result.success(LiveFrame(100, 100, 10L, emptyList()))

        override suspend fun health(): Result<LiveHealth> = error("health n'est pas utilisé par le direct")
    }

    private fun session() = LiveSession(client, clock = { 0L })

    @Test
    fun `sans session rien ne part`() {
        assertFalse(LiveFrameCadence().admits(null, 1_000L))
    }

    @Test
    fun `premier creneau immediat puis cadence adaptative respectee`() {
        val active = session()
        val interval = active.intervalMsAdaptive()
        val cadence = LiveFrameCadence()
        assertTrue(cadence.admits(active, 1_000L))
        assertFalse("juste avant l'échéance : frame jetée", cadence.admits(active, 1_000L + interval - 1))
        assertTrue("à l'échéance : frame admise", cadence.admits(active, 1_000L + interval))
    }

    @Test
    fun `une frame en vol refuse la suivante`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocking = object : LiveClient {
            override suspend fun live(jpegB64: String, prompts: List<String>?, workEdge: Int, maxObjects: Int) =
                run { gate.await(); Result.success(LiveFrame(100, 100, 10L, emptyList())) }

            override suspend fun health(): Result<LiveHealth> = error("inutilisé")
        }
        val active = LiveSession(blocking, clock = { 0L })
        val job = launch { active.runFrame("jpeg") }
        advanceUntilIdle()
        assertTrue("la requête doit être en vol", active.inFlight)
        assertFalse(LiveFrameCadence().admits(active, 5_000L))
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(active.inFlight)
        job.join()
    }

    @Test
    fun `reset rend le creneau immediat`() {
        val active = session()
        val cadence = LiveFrameCadence()
        assertTrue(cadence.admits(active, 1_000L))
        assertFalse(cadence.admits(active, 1_001L))
        cadence.reset()
        assertTrue("nouvelle séance : plus d'échéance en cours", cadence.admits(active, 1_001L))
    }

    @Test
    fun `la cadence suit la latence de la session`() {
        val active = session()
        assertEquals(
            "session sans mesure : plancher rapide",
            dev.sam3vision.core.live.Cadence.Direct.fastMs,
            active.intervalMsAdaptive(),
        )
    }

    @Test
    fun `wantsFrame est admits sans effet de bord`() {
        val active = session()
        val interval = active.intervalMsAdaptive()
        val cadence = LiveFrameCadence()
        assertTrue(cadence.wantsFrame(active, 1_000L))
        assertTrue("lecture seule : l'échéance n'a pas bougé", cadence.admits(active, 1_000L))
        assertFalse(cadence.wantsFrame(active, 1_000L + interval - 1))
        assertTrue(cadence.wantsFrame(active, 1_000L + interval))
        assertFalse("sans pipeline : rien à encoder", cadence.wantsFrame(null, 1_000L))
    }

    @Test
    fun `wantsFrame refuse pendant le vol`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocking = object : LiveClient {
            override suspend fun live(jpegB64: String, prompts: List<String>?, workEdge: Int, maxObjects: Int) =
                run { gate.await(); Result.success(LiveFrame(100, 100, 10L, emptyList())) }

            override suspend fun health(): Result<LiveHealth> = error("inutilisé")
        }
        val active = LiveSession(blocking, clock = { 0L })
        val job = launch { active.runFrame("jpeg") }
        advanceUntilIdle()
        assertTrue("la requête doit être en vol", active.inFlight)
        assertFalse(LiveFrameCadence().wantsFrame(active, 5_000L))
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `bras continu suit la cadence continue sur LiveSession`() {
        val active = session()
        active.stats.record(400L)
        val cadence = LiveFrameCadence().apply { continuous = true }
        val step = active.intervalMsContinuous()
        assertEquals("latence 400 ms : moitié", 200L, step)
        assertTrue(cadence.admits(active, 1_000L))
        assertFalse(cadence.admits(active, 1_000L + step - 1))
        assertTrue(cadence.admits(active, 1_000L + step))
    }

    @Test
    fun `bras continu replie sur l adaptatif hors LiveSession`() {
        val tracker = object : dev.sam3vision.core.live.FramePipeline {
            override val inFlight: Boolean = false
            override fun intervalMsAdaptive(): Long = 400L
            override val generation: Long = 0L
            override fun isCurrent(era: Long): Boolean = true
            override val stats: dev.sam3vision.core.live.LiveStats = dev.sam3vision.core.live.LiveStats()
            override fun reset() {}
        }
        val cadence = LiveFrameCadence().apply { continuous = true }
        assertTrue(cadence.admits(tracker, 1_000L))
        assertFalse("pas de continue hors session : palier adaptatif", cadence.admits(tracker, 1_399L))
        assertTrue(cadence.admits(tracker, 1_400L))
    }
}