package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pipeline multi-frames : il doit lancer plusieurs frames en parallèle, **jeter** au-delà de
 * sa capacité (jamais de file) et n'afficher que la réponse la plus récente. Le client est un
 * faux — le transport HTTP est déjà couvert par `LiveClientTest` — et il compte les entrées
 * dans le client (`CountDownLatch`) pour que la simultanéité soit observée, pas supposée.
 *
 * Les cas ne dépendent jamais de l'ordre d'achèvement des réponses (il n'est pas déterministe) :
 * seul ce qui est garanti est affirmé, et l'anti-rebond est prouvé par un ordre imposé
 * (une réponse lente de 300 ms contre une rapide de 10 ms).
 */
class LivePipelineTest {

    private class FakeClient(
        private val delays: List<Long> = listOf(0),
        private val failNext: Boolean = false,
        private val entered: CountDownLatch? = null,
    ) : LiveClient {

        val calls = Collections.synchronizedList(mutableListOf<Int>())
        private val counter = AtomicInteger(0)

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            val index = counter.getAndIncrement()
            calls += index
            entered?.countDown()
            val ms = delays.getOrElse(index) { delays.lastOrNull() ?: 0L }
            withContext(Dispatchers.IO) { delay(ms) }
            if (failNext) return Result.failure(IllegalStateException("boom"))
            val obj = SegObject(label = "person", score = 0.9f, box = listOf(0f, 0f, 1f, 1f), maskPngB64 = "AA")
            return Result.success(LiveFrame(width = 512, height = 288, elapsedMs = ms, objects = listOf(obj)))
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "fake"))
    }

    private fun pipeline(client: LiveClient, parallel: Int = LivePipeline.DEFAULT_PARALLEL) =
        LivePipeline(client, maxParallel = parallel)

    @Test
    fun `six frames partent en parallele et la septieme est jetee`() = runBlocking {
        val entered = CountDownLatch(LivePipeline.DEFAULT_PARALLEL)
        val client = FakeClient(delays = listOf(150), entered = entered)
        val p = pipeline(client)
        val results = (1..LivePipeline.DEFAULT_PARALLEL)
            .map { async(Dispatchers.Default) { p.submit("AA") } }
        assertTrue("six frames doivent entrer dans le client", entered.await(5, TimeUnit.SECONDS))
        assertEquals(6, p.inFlight)
        assertNull("pipeline plein : frame jetée, jamais mise en file", p.submit("AA"))
        val all = results.awaitAll()
        assertEquals(6, client.calls.size)
        assertEquals(6, p.sent)
        assertEquals(6, all.count { it != null && it.isSuccess })
        assertTrue("au moins une réponse affichée", p.displayed >= 1)
        assertEquals(0, p.inFlight)
        // Une frame partie après coup porte un ticket plus récent : elle s'affiche toujours.
        val after = p.displayed
        p.submit("AA")
        assertEquals(after + 1, p.displayed)
        assertEquals(7, p.sent)
    }

    @Test
    fun `une frame est jetee quand le pipeline est plein`() = runBlocking {
        val client = FakeClient(delays = listOf(200))
        val p = pipeline(client, parallel = 2)
        val results = (1..4).map { async(Dispatchers.Default) { p.submit("AA") } }.awaitAll()
        assertEquals(2, results.count { it == null })
        assertEquals(2, results.count { it != null })
        assertEquals(2, p.sent)
        assertEquals(2, client.calls.size)
        assertEquals(0, p.inFlight)
    }

    @Test
    fun `une reponse plus vieille que la derniere affichee n'est pas comptee`() = runBlocking {
        // 1re requête lente (300 ms), 2e rapide (10 ms) : la rapide arrive d'abord.
        val client = FakeClient(delays = listOf(300, 10))
        val p = pipeline(client, parallel = 4)
        val slow = async { p.submit("AA") }
        delay(30)
        val fast = async { p.submit("AA") }
        fast.await()
        slow.await()
        assertEquals(1, p.displayed)
        assertEquals(2, p.sent)
    }

    @Test
    fun `frame blanche ignoree et intervalles bornes`() = runBlocking {
        val p = pipeline(FakeClient())
        assertNull(p.submit(""))
        assertEquals(0, p.sent)
        assertEquals(33L, p.intervalMsFor(30))
        assertEquals(33L, p.intervalMsFor(60))
        assertEquals(250L, p.intervalMsFor(4))
        assertEquals(500L, p.intervalMsFor(2))
        assertEquals(500L, p.intervalMsFor(1))
        assertEquals(500L, p.intervalMsFor(0))
    }

    @Test
    fun `reset remet les compteurs a zero`() = runBlocking {
        val p = pipeline(FakeClient(delays = listOf(5)))
        p.submit("AA")
        assertEquals(1, p.sent)
        p.reset()
        assertEquals(0, p.sent)
        assertEquals(0, p.displayed)
        assertEquals(0, p.stats.samples)
        assertEquals(0, p.inFlight)
    }

    @Test
    fun `une erreur serveur ne casse pas le pipeline`() = runBlocking {
        val p = pipeline(FakeClient(failNext = true))
        val result = p.submit("AA")
        assertTrue(result != null && result.isFailure)
        assertEquals(1, p.sent)
        assertEquals(0, p.displayed)
        assertEquals(0, p.inFlight)
    }

    @Test
    fun `les latences alimentent les statistiques`() = runBlocking {
        val p = pipeline(FakeClient(delays = listOf(40, 60)))
        p.submit("AA")
        p.submit("AA")
        assertEquals(2, p.stats.samples)
        assertEquals(2, p.serverStats.samples)
        assertTrue(p.serverStats.averageMs >= 40)
    }
}