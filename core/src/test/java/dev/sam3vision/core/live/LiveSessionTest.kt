package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionTest {

    private class FakeLiveClient : LiveClient {
        var result: Result<LiveFrame> = Result.success(LiveFrame(640, 480, 321, emptyList()))
        var onCall: () -> Unit = {}
        var calls = 0
        var lastPrompts: List<String>? = null
        var lastWorkEdge = 0
        var lastMaxObjects = 0

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            calls++
            onCall()
            lastPrompts = prompts
            lastWorkEdge = workEdge
            lastMaxObjects = maxObjects
            return result
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(true, "sam3.1", "cuda", "A100"))
    }

    private fun sessionWith(stats: LiveStats, vararg latencies: Long): LiveSession {
        latencies.forEach(stats::record)
        return LiveSession(FakeLiveClient(), stats = stats)
    }

    @Test
    fun `runFrame consigne elapsed_ms et latence bout-en-bout`() = runBlocking {
        val client = FakeLiveClient()
        var now = 1_000L
        client.onCall = { now += 420 }
        val session = LiveSession(client, clock = { now })

        val frame = session.runFrame("QQ==")?.getOrThrow()

        assertEquals(640, frame?.width)
        assertEquals(1, session.stats.samples)
        assertEquals(420L, session.stats.averageMs)
        assertEquals(321L, session.serverStats.averageMs)
        assertFalse(session.inFlight)
    }

    @Test
    fun `le gate occupe jette la frame sans appeler le client`() = runBlocking {
        val client = FakeLiveClient()
        val gate = FrameGate()
        assertTrue(gate.tryStart())
        val session = LiveSession(client, gate)

        assertNull(session.runFrame("QQ=="))
        assertEquals(0, client.calls)
        gate.finish()
        assertNotNull(session.runFrame("QQ=="))
        assertEquals(1, client.calls)
    }

    @Test
    fun `les prompts sont normalises avant l'envoi`() = runBlocking {
        val client = FakeLiveClient()
        val session = LiveSession(
            client,
            prompts = listOf(" person ", "person", "", "x".repeat(40), "tree"),
        )
        session.runFrame("QQ==")
        assertEquals(listOf("person", "tree"), client.lastPrompts)
        assertEquals(640, client.lastWorkEdge)
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, client.lastMaxObjects)
    }

    @Test
    fun `un echec note la latence mais pas le serveur`() = runBlocking {
        val client = FakeLiveClient()
        client.result = Result.failure(ApiError.Timeout())
        val session = LiveSession(client)

        val failure = session.runFrame("QQ==")?.exceptionOrNull()

        assertTrue(failure is ApiError.Timeout)
        assertEquals(1, session.stats.samples)
        assertEquals(0, session.serverStats.samples)
        assertFalse(session.inFlight)
    }

    @Test
    fun `l'intervalle adaptatif ralentit quand la latence moyenne depasse 900 ms`() {
        assertEquals(900L, sessionWith(LiveStats(), 1_000L, 1_400L).intervalMsAdaptive())
    }

    @Test
    fun `l'intervalle adaptatif accelere quand la latence moyenne est sous 350 ms`() {
        assertEquals(350L, sessionWith(LiveStats(), 120L, 300L).intervalMsAdaptive())
    }

    @Test
    fun `l'intervalle adaptatif reste a 500 ms entre les deux seuils`() {
        assertEquals(500L, sessionWith(LiveStats(), 400L, 600L).intervalMsAdaptive())
        assertEquals(500L, sessionWith(LiveStats(), 900L).intervalMsAdaptive())
        assertEquals(500L, sessionWith(LiveStats(), 350L).intervalMsAdaptive())
    }

    @Test
    fun `la cadence rapide n'impose plus de plancher, elle repart des qu'un creneau se libere`() {
        val fast = LiveSession(FakeLiveClient(), cadence = Cadence.Fast)
        fast.stats.record(220L)
        assertEquals(0L, fast.intervalMsAdaptive())
    }

    @Test
    fun `la cadence rapide ralentit quand le backend rame`() {
        val fast = LiveSession(FakeLiveClient(), cadence = Cadence.Fast)
        fast.stats.record(1_200L)
        assertEquals(900L, fast.intervalMsAdaptive())
    }

    @Test
    fun `le direct plafonne a 8 objets par defaut`() = runBlocking {
        val client = FakeLiveClient()
        LiveSession(client).runFrame("QQ==")
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, client.lastMaxObjects)
        assertEquals(8, client.lastMaxObjects)
    }

    @Test
    fun `le plafond regle dans les reglages est transmis au serveur`() = runBlocking {
        val client = FakeLiveClient()
        LiveSession(client, maxObjects = 7).runFrame("QQ==")
        assertEquals(7, client.lastMaxObjects)
    }

    @Test
    fun `reset vide le gate et les stats`() = runBlocking {
        val session = LiveSession(FakeLiveClient())
        session.runFrame("QQ==")
        session.reset()
        assertEquals(0, session.stats.samples)
        assertEquals(0, session.serverStats.samples)
        assertFalse(session.inFlight)
    }

    @Test
    fun `la cadence rapide garde un seul creneau, le backend serialise`() {
        val session = LiveSession(FakeLiveClient(), cadence = Cadence.Fast)
        assertEquals(1, session.slots)
        assertFalse(session.saturated)
    }

    @Test
    fun `une session de direct garde un seul creneau`() {
        assertEquals(1, LiveSession(FakeLiveClient()).slots)
    }

    @Test
    fun `le regulateur ne touche a rien quand la cadence n_a qu_un creneau`() {
        val session = LiveSession(FakeLiveClient(), cadence = Cadence.Fast)
        (1..3).forEach { _ -> session.stats.record(344L) }
        session.regulate()
        assertEquals(1, session.slots)
    }

    @Test
    fun `le regulateur attend trois mesures avant de refermer les creneaux`() {
        val session = LiveSession(FakeLiveClient(), cadence = twoSlots())
        assertEquals(2, session.slots)
        (1..3).forEach { _ -> session.stats.record(344L) }
        session.regulate()
        assertEquals(1, session.slots)
    }

    @Test
    fun `le regulateur ne rouvre que sous la moitie du budget`() {
        val session = LiveSession(FakeLiveClient(), cadence = twoSlots())
        (1..3).forEach { _ -> session.stats.record(344L) }
        session.regulate()
        assertEquals(1, session.slots)
        // 120 ms : sous le budget (150) mais pas sous sa moitié -> on reste à un créneau
        session.stats.reset()
        (1..3).forEach { _ -> session.stats.record(120L) }
        session.regulate()
        assertEquals(1, session.slots)
        // 60 ms : le backend respire, on rouvre
        session.stats.reset()
        (1..3).forEach { _ -> session.stats.record(60L) }
        session.regulate()
        assertEquals(2, session.slots)
    }

    /** Cadence à deux créneaux : ce que l'app ne demande plus, mais que le régulateur sait gérer. */
    private fun twoSlots() = Cadence(
        fastMs = 0L,
        targetMs = 0L,
        slowMs = 900L,
        concurrency = 2,
        latencyBudgetMs = 150L,
    )

    @Test
    fun `le Direct n_a pas de budget de latence donc garde un creneau`() {
        val session = LiveSession(FakeLiveClient())
        session.stats.record(50L)
        session.regulate()
        assertEquals(1, session.slots)
    }

    @Test
    fun `la cadence rapide ne pose plus de plancher artificiel`() {
        val session = LiveSession(FakeLiveClient(), cadence = Cadence.Fast)
        session.stats.record(161L)
        assertEquals(0L, session.intervalMsAdaptive())
    }
}
