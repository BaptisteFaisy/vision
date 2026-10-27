package dev.sam3vision.core.live

import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.model.TrackHealth
import dev.sam3vision.core.model.TrackSessionStart
import dev.sam3vision.core.model.TrackedObject
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TrackClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun startTracked(id: Int) =
    TrackedObject(id, "person", 0.9f, listOf(0f, 0f, 10f, 10f), "AA==")

private fun startOpened(sessionId: String = "s1") =
    TrackSessionStart(sessionId, 640, 480, listOf(startTracked(1)))

/** Client factice : `start` programmable (bloquant quand [held]), appels comptés. */
private class StartFakeClient(
    var startResult: Result<TrackSessionStart> = Result.success(startOpened()),
) : TrackClient {
    var startCalls = 0
        private set

    /** Quand non-null, `start` attend ce résultat au lieu de répondre aussitôt. */
    var held: CompletableDeferred<Result<TrackSessionStart>>? = null

    /** Acquitté dès qu'un `start` bloqué entre dans le réseau (rendez-vous de test). */
    val entered = CompletableDeferred<Unit>()

    override suspend fun start(imageB64: String, prompts: List<String>): Result<TrackSessionStart> {
        startCalls += 1
        held?.let {
            entered.complete(Unit)
            return it.await()
        }
        return startResult
    }

    override suspend fun frame(sessionId: String, imageB64: String): Result<TrackFrame> =
        Result.success(TrackFrame(640, 480, listOf(startTracked(1))))

    override suspend fun close(sessionId: String): Result<Unit> = Result.success(Unit)

    override suspend fun health(): Result<TrackHealth> =
        Result.success(TrackHealth(true, "sam3.1-tracker", "A100", 16))
}

/** Régulation de [TrackRunner.start] : gate anti-storm et backoff d'échec d'ouverture. */
class TrackRunnerStartTest {

    @Test
    fun `start concurrent echoue vite sans second POST`() = runBlocking {
        val client = StartFakeClient()
        client.held = CompletableDeferred()
        val runner = TrackRunner(client)

        val first = async { runner.start(listOf("person"), "SUVL") }
        client.entered.await()
        val concurrent = runner.start(listOf("person"), "SUVL")

        assertTrue("le second start est refusé", concurrent.isFailure)
        val failure = concurrent.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertEquals(1, client.startCalls)

        client.held?.complete(Result.success(startOpened()))
        assertTrue("le premier start aboutit", first.await().isSuccess)
        assertEquals("toujours un seul POST /session", 1, client.startCalls)
        assertEquals("s1", runner.sessionId)
    }

    @Test
    fun `start en echec rejoue la meme erreur pendant cinq secondes`() = runBlocking {
        val backend404 = ApiError.Http(404, "route absente")
        val client = StartFakeClient(startResult = Result.failure(backend404))
        var now = 0L
        val runner = TrackRunner(client, clock = { now })

        assertTrue(runner.start(listOf("person"), "SUVL").isFailure)
        assertEquals(1, client.startCalls)

        now = TrackRunner.START_RETRY_MS - 1
        val cached = runner.start(listOf("person"), "SUVL")
        assertTrue(cached.isFailure)
        assertSame("même erreur rejouée, pas de nouveau POST", backend404, cached.exceptionOrNull())
        assertEquals(1, client.startCalls)

        now = TrackRunner.START_RETRY_MS
        val retried = runner.start(listOf("person"), "SUVL")
        assertTrue(retried.isFailure)
        assertEquals("nouvel essai après la fenêtre", 2, client.startCalls)
    }

    @Test
    fun `un start en succes rearme apres un echec`() = runBlocking {
        val client = StartFakeClient(startResult = Result.failure(ApiError.Http(404, "route absente")))
        var now = 0L
        val runner = TrackRunner(client, clock = { now })

        assertTrue(runner.start(listOf("person"), "SUVL").isFailure)

        client.startResult = Result.success(startOpened())
        now = TrackRunner.START_RETRY_MS
        assertTrue("le succès ré-arme", runner.start(listOf("person"), "SUVL").isSuccess)
        assertEquals(2, client.startCalls)

        val fresh = ApiError.Http(503, "gpu coupé")
        client.startResult = Result.failure(fresh)
        now += 1_000L
        assertTrue(runner.start(listOf("person"), "SUVL").isFailure)
        assertEquals("nouvel échec = nouvel essai réel", 3, client.startCalls)

        now += TrackRunner.START_RETRY_MS - 1
        val cached = runner.start(listOf("person"), "SUVL")
        assertSame("fenêtre ancrée sur le second échec", fresh, cached.exceptionOrNull())
        assertEquals(3, client.startCalls)
        assertEquals("le refus rejoué ne touche pas la session", "s1", runner.sessionId)
    }
}
