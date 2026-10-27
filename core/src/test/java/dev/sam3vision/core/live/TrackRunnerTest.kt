package dev.sam3vision.core.live

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.model.TrackHealth
import dev.sam3vision.core.model.TrackSessionStart
import dev.sam3vision.core.model.TrackedObject
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.HttpTrackClient
import dev.sam3vision.core.net.TrackClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tracked(id: Int, label: String = "person") =
    TrackedObject(id, label, 0.9f, listOf(0f, 0f, 10f, 10f), "AA==")

/** Frame attendue : le créneau est libre, la session est ouverte, le backend répond. */
private suspend fun TrackRunner.frameOf(jpegB64: String): TrackFrame =
    checkNotNull(submit(jpegB64)).getOrThrow()

private fun objectJson(id: Int, score: Double) =
    """{"id":$id,"label":"person","score":$score,"box":[1,1,2,2],"mask_b64":"QQ=="}"""

private fun startJson(sessionId: String) =
    """{"session_id":"$sessionId","width":800,"height":600,"objects":[${objectJson(42, 0.8)}]}"""

private fun frameJson() =
    """{"width":800,"height":600,"objects":[${objectJson(42, 0.7)}]}"""

private class FakeTrackClient(
    var startResult: Result<TrackSessionStart> =
        Result.success(TrackSessionStart("s1", 640, 480, listOf(tracked(1)))),
    var frameResult: Result<TrackFrame> = Result.success(TrackFrame(640, 480, listOf(tracked(1)))),
    var closeResult: Result<Unit> = Result.success(Unit),
) : TrackClient {
    val calls = mutableListOf<String>()

    override suspend fun start(imageB64: String, prompts: List<String>): Result<TrackSessionStart> {
        calls += "start:${prompts.joinToString(",")}"
        return startResult
    }

    override suspend fun frame(sessionId: String, imageB64: String): Result<TrackFrame> {
        calls += "frame:$sessionId"
        return frameResult
    }

    override suspend fun close(sessionId: String): Result<Unit> {
        calls += "close:$sessionId"
        return closeResult
    }

    override suspend fun health(): Result<TrackHealth> {
        calls += "health"
        return Result.success(TrackHealth(true, "sam3.1-tracker", "A100", 16))
    }
}

class TrackRunnerTest {

    @Test
    fun `start ouvre la session avec des prompts normalises`() = runBlocking {
        val client = FakeTrackClient()
        val runner = TrackRunner(client)
        runner.start(listOf("  person ", "person", ""), "SUVL").getOrThrow()

        assertEquals("s1", runner.sessionId)
        assertEquals(640, runner.width)
        assertEquals(480, runner.height)
        assertEquals(listOf("start:person"), client.calls)
    }

    @Test
    fun `start rend la frame d ouverture avec ses objets`() = runBlocking {
        val client = FakeTrackClient(
            startResult = Result.success(TrackSessionStart("s1", 800, 600, listOf(tracked(42)))),
        )
        val opened = TrackRunner(client).start(listOf("person"), "SUVL").getOrThrow()

        assertEquals(800, opened.width)
        assertEquals(600, opened.height)
        assertEquals(42, opened.objects.single().id)
    }

    @Test
    fun `start sans prompts retombe sur les prompts par defaut`() = runBlocking {
        val client = FakeTrackClient()
        TrackRunner(client).start(emptyList(), "SUVL").getOrThrow()
        assertEquals("start:${LivePrompts.Default.joinToString(",")}", client.calls.single())
    }

    @Test
    fun `submit renvoie les objets suivis avec leur id`() = runBlocking {
        val objects = listOf(tracked(7), tracked(9, "dog"))
        val client = FakeTrackClient(frameResult = Result.success(TrackFrame(640, 480, objects)))
        val runner = TrackRunner(client)
        runner.start(listOf("person"), "SUVL").getOrThrow()
        val frame = runner.frameOf("QUJD")

        assertEquals(listOf(7, 9), frame.objects.map { it.id })
        assertEquals("dog", frame.objects[1].label)
        assertEquals(listOf("start:person", "frame:s1"), client.calls)
    }

    @Test
    fun `submit complete la taille manquante avec celle de la session`() = runBlocking {
        val client = FakeTrackClient(frameResult = Result.success(TrackFrame(0, 0, listOf(tracked(3)))))
        val runner = TrackRunner(client)
        runner.start(listOf("car"), "SUVL").getOrThrow()
        val frame = runner.frameOf("QUJD")

        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
    }

    @Test
    fun `submit jette la frame quand le creneau est pris`() = runBlocking {
        val client = FakeTrackClient()
        val gate = FrameGate()
        val runner = TrackRunner(client, gate)
        runner.start(listOf("person"), "SUVL").getOrThrow()

        assertTrue(gate.tryStart())
        assertTrue(runner.inFlight)
        assertNull(runner.submit("QUJD"))
        assertEquals(0, client.calls.count { it.startsWith("frame") })
    }

    @Test
    fun `submit sans session est une erreur`() = runBlocking {
        val runner = TrackRunner(FakeTrackClient())
        val failure = runner.submit("QUJD")?.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
    }

    @Test
    fun `session perdue recree exactement deux sessions`() {
        var opened = 0
        val server = TestHttpServer { request ->
            when {
                request.path == "/session" && request.method == "POST" -> {
                    opened += 1
                    TestHttpServer.Response(200, startJson("s$opened"))
                }
                request.path == "/frame" -> {
                    val sent = JSONObject(request.body).getString("session_id")
                    if (sent == "s1") TestHttpServer.Response(404, """{"detail":"session inconnue"}""")
                    else TestHttpServer.Response(200, frameJson())
                }
                else -> TestHttpServer.Response(400, "{}")
            }
        }
        try {
            val runner = TrackRunner(HttpTrackClient(server.baseUrl))
            val frame = runBlocking {
                runner.start(listOf("person"), "SUVL").getOrThrow()
                runner.frameOf("QUJD")
            }

            assertEquals(2, opened)
            assertEquals("s2", runner.sessionId)
            assertEquals(42, frame.objects.single().id)
            assertEquals(800, frame.width)
            assertEquals(1, server.requests.count { it.path == "/frame" })
        } finally {
            server.close()
        }
    }

    @Test
    fun `une session toujours perdue ne relance pas de boucle infinie`() = runBlocking {
        val client = FakeTrackClient(
            startResult = Result.success(TrackSessionStart("s2", 100, 100, listOf(tracked(5)))),
            frameResult = Result.failure(ApiError.Http(404, "session inconnue")),
        )
        val runner = TrackRunner(client)
        runner.start(listOf("person"), "SUVL").getOrThrow()
        val frame = runner.frameOf("QUJD")

        assertEquals(2, client.calls.count { it.startsWith("start") })
        assertEquals(1, client.calls.count { it.startsWith("frame") })
        assertEquals(5, frame.objects.single().id)
        assertEquals("s2", runner.sessionId)
    }

    @Test
    fun `la recreation qui echoue remonte l erreur sans nouvelle session`() = runBlocking {
        val client = FakeTrackClient(
            startResult = Result.failure(ApiError.Http(500, "gpu indisponible")),
        )
        val runner = TrackRunner(client)
        val started = runner.start(listOf("person"), "SUVL")

        assertTrue(started.isFailure)
        assertNull(runner.sessionId)
        assertEquals(1, client.calls.count { it.startsWith("start") })
    }

    @Test
    fun `stop ferme la session puis ne fait plus rien`() = runBlocking {
        val client = FakeTrackClient()
        val runner = TrackRunner(client)
        runner.start(listOf("person"), "SUVL").getOrThrow()
        runner.stop()
        runner.stop()

        assertEquals(listOf("start:person", "close:s1"), client.calls)
        assertNull(runner.sessionId)
        assertNotNull(runner.submit("QUJD"))
    }

    @Test
    fun `stats enregistrent la latence bout en bout`() = runBlocking {
        // start horodate la session (rekey), puis la frame : début et fin.
        val times = longArrayOf(1_000L, 1_000L, 1_250L)
        var tick = 0
        fun clock(): Long = times[tick.coerceAtMost(times.size - 1)].also { tick += 1 }
        val runner = TrackRunner(FakeTrackClient(), clock = ::clock)
        runner.start(listOf("person"), "SUVL").getOrThrow()
        runner.frameOf("QUJD")

        assertEquals(1, runner.stats.samples)
        assertEquals(250L, runner.stats.averageMs)
    }

    @Test
    fun `reset revoque la frame en vol et oublie les mesures`() = runBlocking {
        val runner = TrackRunner(FakeTrackClient())
        runner.start(listOf("person"), "SUVL").getOrThrow()
        val era = runner.generation
        runner.frameOf("QUJD")
        assertEquals(1, runner.stats.samples)

        runner.reset()

        assertFalse("la réponse en vol est périmée", runner.isCurrent(era))
        assertEquals(0, runner.stats.samples)
    }

    @Test
    fun `submit rouvre une session quand l age depasse le rekey`() = runBlocking {
        val client = FakeTrackClient()
        var now = 0L
        val runner = TrackRunner(client, clock = { now }, rekeyMs = 3_000L)
        runner.start(listOf("person"), "SUVL").getOrThrow()

        now = 1_000L
        runner.frameOf("QUJD")
        assertEquals(listOf("start:person", "frame:s1"), client.calls)

        now = 4_000L
        client.startResult = Result.success(TrackSessionStart("s2", 640, 480, listOf(tracked(2))))
        val frame = runner.frameOf("QUJD")

        assertEquals("s2", runner.sessionId)
        assertEquals(2, frame.objects.single().id)
        assertEquals(listOf("start:person", "frame:s1", "start:person", "close:s1"), client.calls)
    }

    @Test
    fun `rekey en echec replie sur l ancienne session`() = runBlocking {
        val client = FakeTrackClient()
        var now = 0L
        val runner = TrackRunner(client, clock = { now }, rekeyMs = 3_000L)
        runner.start(listOf("person"), "SUVL").getOrThrow()

        now = 5_000L
        client.startResult = Result.failure(ApiError.Network("backend mort"))
        val frame = runner.frameOf("QUJD")

        assertEquals("s1", runner.sessionId)
        assertEquals(1, frame.objects.single().id)
        assertTrue(client.calls.contains("frame:s1"))
        assertFalse(client.calls.any { it.startsWith("close") })
    }

    @Test
    fun `intervalMsFor borne la cadence a trente fps minimum et deux fps maximum`() {
        val runner = TrackRunner(FakeTrackClient())
        assertEquals(33L, runner.intervalMsFor(30))
        assertEquals(33L, runner.intervalMsFor(60))
        assertEquals(100L, runner.intervalMsFor(10))
        assertEquals(500L, runner.intervalMsFor(2))
        assertEquals(500L, runner.intervalMsFor(1))
        assertEquals(500L, runner.intervalMsFor(0))
        assertEquals(30, TrackRunner.TARGET_FPS)
        assertEquals("la cadence du direct reste au plancher", 33L, runner.intervalMsAdaptive())
    }
}