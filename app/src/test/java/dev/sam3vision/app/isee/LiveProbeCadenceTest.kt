package dev.sam3vision.app.isee

import dev.sam3vision.core.live.FrameGate
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
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

    @Test
    fun `avec detections la frame suivante saute l_echeance`() = runTest {
        val journal = mutableListOf<String>()
        val fake = ProbeTestClient(journal)
        val probe = LiveProbe(
            scope = this,
            clock = clock,
            background = StandardTestDispatcher(testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
        )
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        probe.configure(ENDPOINT)
        probe.begin()
        // Chauffe : la session chaude est active, puis le backend ralentit (900 ms).
        probe.onFrame("A0", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        fake.respond("A0", frame())
        advanceUntilIdle()
        val session = probe.session ?: error("session chaude attendue")
        repeat(3) { session.stats.record(10_000L) }
        probe.onFrame("A", captureAtNs = 2_000_000L)
        advanceUntilIdle()
        assertEquals(2, fake.sent)
        fake.respond("A", LiveFrame(width = 512, height = 1138, elapsedMs = 100L, objects = listOf(personne())))
        advanceUntilIdle()
        assertEquals(1, probe.state.value.objects)
        // Échéance fermée (900 ms) mais détections à l'écran : B part quand même.
        probe.onFrame("B", captureAtNs = 3_000_000L)
        advanceUntilIdle()
        assertEquals("priorité aux frames avec détection", 3, fake.sent)
        fake.respond("B", frame())
        advanceUntilIdle()
    }

    @Test
    fun `par defaut le scheduler retient la deuxieme frame rapprochee`() = runTest {
        val journal = mutableListOf<String>()
        val fake = BlockingClient(journal)
        val probe = LiveProbe(
            scope = this,
            clock = clock,
            background = StandardTestDispatcher(testScheduler),
            clientFor = { _, _, _ -> fake },
        )
        probe.configure(ENDPOINT)
        probe.begin()

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        probe.onFrame("B", captureAtNs = 2_000_000L)
        advanceUntilIdle()
        assertEquals("keyframe en vol : B retenue, pas envoyée", 1, fake.sent)

        at(40)
        fake.release(frame())
        advanceUntilIdle()
        assertEquals("B retenue ne part pas au drain", 1, fake.sent)
        assertEquals("seule A est publiée", 1, probe.state.value.frames)
    }

    /** Personne au centre, sans masque (inutile à la priorité, aucun Android à moquer). */
    private fun personne() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(206f, 400f, 306f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    /** Horloge monotone pilotée (ns), comme `imageInfo.timestamp`. */
    private var ns = 0L
    private val clock: () -> Long get() = { ns }

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame sans objet : aucun masque à décorer, aucun Android à moquer. */
    private fun frame() = LiveFrame(width = 512, height = 1138, elapsedMs = 100L, objects = emptyList())

    @Test
    fun `cinq frames pendant deux vols, les fraiches partent, la plus recente gagne`() = runTest {
        val journal = mutableListOf<String>()
        val fake = BlockingClient(journal)
        val probe = probe(this, fake)

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        assertEquals("A part aussitôt", 1, fake.sent)
        assertTrue("créneau libre : la caméra peut envoyer", probe.wantsFrame())

        probe.onFrame("B", captureAtNs = 2_000_000L)
        advanceUntilIdle()
        assertEquals("deuxième créneau : B part aussitôt", 2, fake.sent)
        assertTrue("saturé, réserve vide : la caméra prépare la suivante", probe.wantsFrame())
        probe.onFrame("C", captureAtNs = 3_000_000L)
        assertFalse("réserve pleine : plus aucune conversion à payer", probe.wantsFrame())
        probe.onFrame("D", captureAtNs = 4_000_000L)
        probe.onFrame("E", captureAtNs = 5_000_000L)
        advanceUntilIdle()
        assertEquals("rien n'est envoyé pendant les vols", 2, fake.sent)

        at(40)
        fake.release(frame())
        advanceUntilIdle()

        assertEquals("A, B puis UNE seule frame : jamais quatre ni cinq", 3, fake.sent)
        assertEquals("la plus récente gagne, les autres sont jetées", listOf("A", "B", "E"), journal)
        assertEquals("les trois frames sont publiées", 3, probe.state.value.frames)
        assertEquals("le refus est compté, pas silencieux", 1L, probe.state.value.skippedFrames)
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
        probe.onFrame("C", captureAtNs = 3_000_000L)
        advanceUntilIdle()
        probe.begin()

        at(40)
        fake.release(frame())
        advanceUntilIdle()
        assertEquals("C datait d'avant : elle est jetée, pas renvoyée", 2, fake.sent)
        assertEquals(listOf("A", "B"), journal)
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
     * Réserve pleine mais périmée (> 50 ms) pendant le vol : la caméra est invitée
     * à ré-encoder (remplacement, jamais de file) pour drainer une frame fraîche —
     * une frame âgée d'un RTT gonflerait le poste `up` pour rien.
     */
    @Test
    fun `reserve perimee pendant le vol, la camera re-encode`() = runTest {
        val fake = BlockingClient(mutableListOf())
        val probe = probe(this, fake)

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 2_000_000L)
        assertFalse("réserve fraîche : pas de conversion", probe.wantsFrame())

        at(10)
        assertFalse("10 ms : encore fraîche", probe.wantsFrame())
        at(60)
        assertTrue("60 ms : périmée, on réalimente", probe.wantsFrame())
        assertEquals("aucun envoi pendant le vol de A", 1, fake.sent)
        fake.release(frame())
        advanceUntilIdle()
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
        // Scheduler coupé : ce test verrouille la cadence, pas les keyframes (voir LiveAdaptiveTest).
        built.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
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

        /** Livre LA réponse, partagée par les vols en cours (même analyse pour tous). */
        fun release(frame: LiveFrame) {
            reply.complete(Result.success(frame))
        }

        /** `/health` négatif aussitôt : pas de réveil précoce (cadence seule, vols sur la froide), et la coroutine de réveil termine — un `/health` suspendu pendrait `runTest`. */
        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = false, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}