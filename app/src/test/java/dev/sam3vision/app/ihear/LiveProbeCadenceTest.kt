package dev.sam3vision.app.ihear

import dev.sam3vision.core.live.FrameGate
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle de cadence, verrouillée **au niveau de la sonde** : une seule frame en vol,
 * au plus une frame en réserve, et la suivante est **jetée** — jamais empilée.
 *
 * Ce que `LiveProbeSchedulingTest` ne dit pas : ce qui arrive quand quatre frames tombent
 * pendant un seul aller-retour. Réponse attendue : une seule part, la plus récente, et la
 * caméra n'est même plus invitée à convertir les autres (convertir un JPEG qui sera jeté
 * est exactement le CPU que cette mission cherche à économiser).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveProbeCadenceTest {

    /** Horloge monotone pilotée (ns), comme `imageInfo.timestamp`. */
    private var ns = 0L
    private val clock: () -> Long get() = { ns }

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame sans objet : aucun masque à décorer, aucun Android à moquer. */
    private fun frame() = LiveFrame(width = 512, height = 1138, elapsedMs = 100L, objects = emptyList())

    @Test
    fun `quatre frames pendant un aller-retour, une seule part, la plus recente`() = runTest {
        val journal = mutableListOf<String>()
        val fake = BlockingClient(journal)
        val probe = probe(this, fake)

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        assertEquals("A part aussitôt", 1, fake.sent)
        assertTrue("créneau pris : la caméra peut préparer une réserve", probe.wantsFrame())

        probe.onFrame("B", captureAtNs = 2_000_000L)
        assertFalse("réserve pleine : plus aucune conversion à payer", probe.wantsFrame())
        probe.onFrame("C", captureAtNs = 3_000_000L)
        probe.onFrame("D", captureAtNs = 4_000_000L)
        advanceUntilIdle()
        assertEquals("rien n'est envoyé pendant le vol de A", 1, fake.sent)

        at(40)
        fake.release(frame())
        advanceUntilIdle()

        assertEquals("A puis UNE seule frame : jamais trois ni quatre", 2, fake.sent)
        assertEquals("la plus récente gagne, les autres sont jetées", listOf("A", "D"), journal)
        assertEquals("les deux frames sont publiées", 2, probe.state.value.frames)
    }

    @Test
    fun `un nouvel enregistrement ne ressert pas la frame d avant`() = runTest {
        val journal = mutableListOf<String>()
        val fake = BlockingClient(journal)
        val probe = probe(this, fake)

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 2_000_000L)
        probe.begin()

        at(40)
        fake.release(frame())
        advanceUntilIdle()
        assertEquals("B datait d'avant : elle est jetée, pas renvoyée", 1, fake.sent)
    }

    @Test
    fun `sans session, la sonde ne demande aucune frame`() = runTest {
        val probe = LiveProbe(
            scope = this,
            clock = clock,
            background = StandardTestDispatcher(testScheduler),
            clientFor = { _, _, _ -> BlockingClient(mutableListOf()) },
        )
        probe.configure(baseUrl = "")
        assertFalse("endpoint vide : rien à capturer", probe.wantsFrame())
        probe.onFrame("A", captureAtNs = 1L)
        advanceUntilIdle()
        assertEquals("frame ignorée, aucun envoi", 0, probe.state.value.frames)
    }

    /**
     * Un seul créneau, décidé par `Cadence.Fast` : la sonde ne peut pas ouvrir deux vols
     * à la fois, même si la caméra presse. Vérifié sur le régulateur lui-même.
     */
    @Test
    fun `le gate d un creneau refuse la seconde frame en vol`() {
        val gate = FrameGate(slots = 1)
        assertTrue(gate.tryStart())
        assertEquals("plus aucun créneau libre", 0, gate.freeSlots)
        assertFalse("la suivante serait jetée", gate.tryStart())
        gate.finish()
        assertTrue("libéré, une frame peut repartir", gate.tryStart())
    }

    /** Sonde branchée sur un client sans transport, sans réseau ni GPU. */
    private fun probe(scope: TestScope, fake: BlockingClient): LiveProbe {
        val built = LiveProbe(
            scope = scope,
            clock = clock,
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, _ -> fake },
        )
        built.configure(ENDPOINT)
        built.begin()
        return built
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
    }

    /**
     * Client de test : consigne chaque envoi dans [journal] et **attend** que le test
     * livre la réponse. Toutes les frames partagent une seule réponse, ce qui suffit
     * puisque la règle veut qu'une seule frame suive la première.
     */
    private class BlockingClient(private val journal: MutableList<String>) : LiveClient {

        var sent: Int = 0
            private set

        private val reply = CompletableDeferred<Result<LiveFrame>>()

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            sent += 1
            journal += jpegB64
            return reply.await()
        }

        /** Livre LA réponse : une seule frame doit être partie quand elle arrive. */
        fun release(frame: LiveFrame) {
            reply.complete(Result.success(frame))
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}