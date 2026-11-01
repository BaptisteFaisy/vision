package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WireMarks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Client de test sans transport : chaque `live` consigne son envoi — avec le nombre de
 * frames déjà publiées à cet instant — et **attend** que le test livre la réponse, dans
 * l'ordre et au moment qu'il choisit.
 */
internal class ProbeTestClient(private val journal: MutableList<String>) : LiveClient {

    /** Chronologie de l'écran : le transport daterait ici la fin du corps. */
    var marks: WireMarks? = null

    /** Frames publiées à l'instant de l'envoi : la preuve que le cycle ne s'arrête pas. */
    var publishedFrames: () -> Int = { -1 }

    var sent = 0
        private set

    /** Concepts envoyés par `live`, dans l'ordre : la preuve de la rotation. */
    val sentPrompts = mutableListOf<List<String>?>()

    /** Sessions fermées (`stop`, `configure`) : le socket rendu, la carte peut s'éteindre. */
    var closed = 0
        private set

    private val waiting = mutableMapOf<String, CompletableDeferred<Result<LiveFrame>>>()

    /** Verrou du journal et des créneaux : deux vols appellent `live` en même temps. */
    private val lock = Any()

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val slot = CompletableDeferred<Result<LiveFrame>>()
        synchronized(lock) {
            waiting[jpegB64] = slot
            sent += 1
            sentPrompts += prompts
            journal += "envoi $jpegB64 (frames=${publishedFrames()})"
        }
        return slot.await()
    }

    /** Livre la réponse de [jpeg] : c'est le test qui décide de l'ordre des frames. */
    fun respond(jpeg: String, frame: LiveFrame) {
        val slot = synchronized(lock) {
            waiting.remove(jpeg) ?: error("aucune frame « $jpeg » en attente")
        }
        marks?.onBody()
        synchronized(lock) { journal += "reponse $jpeg" }
        slot.complete(Result.success(frame))
    }

    /** Appels `/health` (réveil explicite) reçus par le faux client. */
    var healthCalls = 0

    /** Quand non-null, `/health` attend ce résultat : le réveil précoce est suspendu. */
    var healthGate: CompletableDeferred<Result<LiveHealth>>? = null

    override suspend fun health(): Result<LiveHealth> {
        healthCalls += 1
        healthGate?.let { return it.await() }
        return Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }

    override fun close() {
        closed += 1
    }
}

/** Endpoint factice partagé par les tests de la sonde. */
internal const val PROBE_TEST_ENDPOINT = "https://sam.test"

/**
 * Sonde branchée sur le faux client partagé, sans réseau ni GPU : configurée et
 * commencée, prête à recevoir des frames. Factorisé ici pour ne pas dupliquer le
 * montage dans chaque test de la sonde.
 */
internal fun probeHarness(scope: TestScope): Pair<LiveProbe, ProbeTestClient> {
    val fake = ProbeTestClient(mutableListOf())
    val probe = LiveProbe(
        scope = scope,
        clock = { 0L },
        background = StandardTestDispatcher(scope.testScheduler),
        clientFor = { _, _, _ -> fake },
    )
    probe.configure(PROBE_TEST_ENDPOINT)
    probe.begin()
    return probe to fake
}
