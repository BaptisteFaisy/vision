package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * « Stop » doit vraiment arrêter. Deux défauts réels du mode Direct, signalés par la
 * découpe de `LiveViewModel` et **non corrigés** :
 *
 * 1. la frame en vol survivait à l'arrêt : sa réponse tardive revenait comme un succès,
 *    l'écran se rallumait tout seul et écrasait l'état « arrêté » ;
 * 2. le créneau rendu par cette frame périmée était **volé** à la frame de la séance
 *    suivante (le régulateur croyait la voie libre alors qu'une requête tournait).
 *
 * Ce que ces tests fixent : après [LiveSession.reset] (l'appel d'un arrêt), une réponse
 * partie avant est **jetée** (`runFrame` rend `null`), ne touche ni les statistiques ni
 * le créneau de la séance suivante. La règle produit ne bouge pas : une seule frame en
 * vol, la suivante est jetée.
 */
class LiveSessionStopTest {

    /**
     * Client qui **retient** chaque requête : c'est le test qui décide quand la réponse
     * arrive, donc ce qui arrive « après l'arrêt ».
     */
    private class HeldLiveClient : LiveClient {

        private val arrivals = Channel<String>(Channel.UNLIMITED)
        private val held = mutableMapOf<String, CompletableDeferred<Result<LiveFrame>>>()

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            val slot = CompletableDeferred<Result<LiveFrame>>()
            synchronized(held) { held[jpegB64] = slot }
            arrivals.send(jpegB64)
            return slot.await()
        }

        /** Attend qu'une frame soit réellement en vol et rend son nom. */
        suspend fun nextArrival(): String = arrivals.receive()

        /** Livre la réponse de [jpegB64] : tardive si l'arrêt est déjà passé. */
        fun release(jpegB64: String, result: Result<LiveFrame>) {
            val slot = synchronized(held) { held.remove(jpegB64) }
            requireNotNull(slot) { "aucune frame « $jpegB64 » en vol" }
            slot.complete(result)
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(true, "sam3.1", "cuda", "A100"))
    }

    private fun frame(width: Int = 640) = LiveFrame(width, 480, 321, emptyList())

    @Test
    fun `une reponse qui arrive apres l arret est jetee`() = runBlocking {
        val client = HeldLiveClient()
        val session = LiveSession(client)
        val outcome = CompletableDeferred<Result<LiveFrame>?>()

        val job = launch(Dispatchers.Default) { outcome.complete(session.runFrame("A")) }
        assertEquals("A", client.nextArrival())
        assertTrue("la frame est en vol", session.inFlight)

        session.reset() // ce que fait un « Stop »
        assertFalse("l'arrêt rend le créneau tout de suite", session.inFlight)

        client.release("A", Result.success(frame()))
        job.join()

        assertNull("la réponse tardive ne doit pas être publiée", outcome.await())
        assertEquals("et elle ne doit pas compter dans les statistiques", 0, session.stats.samples)
        assertEquals(0, session.serverStats.samples)
        assertFalse(session.inFlight)
    }

    @Test
    fun `la frame perimee ne rend pas le creneau de la suivante`() = runBlocking {
        val client = HeldLiveClient()
        val session = LiveSession(client)
        val stale = CompletableDeferred<Result<LiveFrame>?>()

        val first = launch(Dispatchers.Default) { stale.complete(session.runFrame("A")) }
        assertEquals("A", client.nextArrival())
        session.reset()

        val second = launch(Dispatchers.Default) { session.runFrame("B") }
        assertEquals("la séance suivante part", "B", client.nextArrival())
        assertTrue("B tient le seul créneau", session.inFlight)

        // La réponse de A tombe maintenant : elle appartient à l'époque révolue.
        client.release("A", Result.success(frame(320)))
        first.join()

        assertTrue("le créneau de B n'est pas volé par la frame périmée", session.inFlight)
        assertNull(stale.await())

        client.release("B", Result.success(frame(512)))
        second.join()
        assertEquals("seule B compte : la réponse de A est jetée", 1, session.stats.samples)
        assertFalse(session.inFlight)
    }

    @Test
    fun `apres l arret la seance repart proprement`() = runBlocking {
        val client = HeldLiveClient()
        val session = LiveSession(client)
        session.stats.record(1_400L) // backend lent : la cadence était ralentie
        assertEquals(900L, session.intervalMsAdaptive())

        session.reset()

        assertEquals("les mesures de la séance précédente sont oubliées", 0, session.stats.samples)
        assertEquals(
            "la cadence repart du plancher, pas du direct précédent",
            Cadence.Direct.fastMs,
            session.intervalMsAdaptive(),
        )
        assertFalse("aucune frame en vol", session.inFlight)
        assertFalse("la voie est libre", session.saturated)

        val next = launch(Dispatchers.Default) { session.runFrame("C") }
        assertEquals("C", client.nextArrival())
        assertTrue("la frame suivante est admise", session.inFlight)
        client.release("C", Result.success(frame()))
        next.join()
        assertFalse(session.inFlight)
    }
}