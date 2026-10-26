package dev.sam3vision.app.ihear

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WireMarks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le cycle iHear ne doit plus rendre le GPU inactif entre « réponse reçue » et « frame
 * suivante envoyée » : la frame en file part dès que le créneau se libère, la publication
 * (masques, distances, état) vient **après**. Et comme la chronologie est par frame, la
 * suivante ne vole pas les postes de celle qu'on est en train d'afficher.
 */
class LiveProbeSchedulingTest {

    /** Horloge monotone pilotée (ns) : même base qu'`imageInfo.timestamp`. */
    private var ns = 0L
    private val clock: () -> Long get() = { ns }

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame sans objet (aucun masque à décorer, aucun Android à moquer) et sa durée serveur. */
    private fun frame(elapsedMs: Long) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = emptyList())

    @Test
    fun `la frame suivante part avant la publication des masques de la precedente`() = runTest {
        val journal = mutableListOf<String>()
        val (probe, fake) = harness(this, journal)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        assertEquals("une frame partie", 1, fake.sent)
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        assertEquals("créneau pris : B attend en file", 1, fake.sent)
        assertEquals(0, probe.state.value.frames)

        at(40)
        fake.respond("A", frame(111))
        advanceUntilIdle()

        assertEquals(2, fake.sent)
        assertTrue(
            "B doit partir pendant que A n'est pas encore publiée : $journal",
            journal.contains("envoi B (frames=0)"),
        )
        assertEquals("publication de A venue juste après", 1, probe.state.value.frames)

        at(60)
        fake.respond("B", frame(222))
        advanceUntilIdle()
        assertEquals(2, probe.state.value.frames)
    }

    @Test
    fun `les marques d_une frame ne finissent pas sur la suivante`() = runTest {
        val journal = mutableListOf<String>()
        val (probe, fake) = harness(this, journal)
        at(10)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        at(15)
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()

        // A répond à 40 ms : la frame B est envoyée avant que A soit publiée.
        at(40)
        fake.respond("A", frame(111))
        advanceUntilIdle()
        val a = probe.state.value.timings
        assertEquals("l'aller-retour de A, sur ses marques à elle", 30L, a.sentToReceivedMs)
        assertEquals(2L, a.captureToJpegMs)
        assertEquals(111L, a.serverTotalMs)

        at(60)
        fake.respond("B", frame(222))
        advanceUntilIdle()
        val b = probe.state.value.timings
        assertEquals("B paye son propre aller-retour, pas celui de A", 20L, b.sentToReceivedMs)
        assertEquals(1L, b.captureToJpegMs)
        // Le temps d'attente en file est mesuré sur B, et sur elle seule.
        assertEquals(25L, b.jpegToSentMs)
        assertEquals(222L, b.serverTotalMs)
    }

    /** Sonde branchée sur un faux client, sans réseau ni GPU : journal ordonné des envois. */
    private fun harness(scope: TestScope, journal: MutableList<String>): Pair<LiveProbe, FakeLiveClient> {
        val fake = FakeLiveClient(journal)
        val probe = LiveProbe(
            scope = scope,
            clock = clock,
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
        )
        fake.publishedFrames = { probe.state.value.frames }
        probe.configure(ENDPOINT)
        probe.begin()
        return probe to fake
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
    }

    /**
     * Client de test sans transport : chaque `live` consigne son envoi — avec le nombre de
     * frames déjà publiées à cet instant — et **attend** que le test livre la réponse, dans
     * l'ordre et au moment qu'il choisit.
     */
    private class FakeLiveClient(private val journal: MutableList<String>) : LiveClient {

        /** Chronologie de l'écran : le transport daterait ici la fin du corps. */
        var marks: WireMarks? = null

        /** Frames publiées à l'instant de l'envoi : la preuve que le cycle ne s'arrête pas. */
        var publishedFrames: () -> Int = { -1 }

        var sent = 0
            private set

        private val waiting = mutableMapOf<String, CompletableDeferred<Result<LiveFrame>>>()

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            val slot = CompletableDeferred<Result<LiveFrame>>()
            waiting[jpegB64] = slot
            sent += 1
            journal += "envoi $jpegB64 (frames=${publishedFrames()})"
            return slot.await()
        }

        /** Livre la réponse de [jpeg] : c'est le test qui décide de l'ordre des frames. */
        fun respond(jpeg: String, frame: LiveFrame) {
            val slot = waiting.remove(jpeg) ?: error("aucune frame « $jpeg » en attente")
            marks?.onBody()
            journal += "reponse $jpeg"
            slot.complete(Result.success(frame))
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}
