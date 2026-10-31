package dev.sam3vision.core.depth

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sérialise les inférences natives d'un estimateur et reporte sa fermeture :
 * un seul `run()` à la fois (les sessions ONNX ne sont pas thread-safe)
 * et `close()` ne libère jamais pendant un `run()` en vol — la libération est
 * alors faite par le run finissant, sans bloquer l'appelant de `close()`.
 *
 * Couvre le SIGABRT Scudo du 2026-10-28 (`libonnxruntime.so`, `close()` depuis
 * le direct pendant un `run()` sur `Dispatchers.Default`) : `reset()` du direct
 * peut fermer pendant qu'une inférence tourne, le run finit sur une session
 * valide puis libère, les runs suivants sont refusés proprement.
 *
 * L'attente du verrou est **mesurée** ([runs], [contendedRuns], [totalWaitMs],
 * [maxWaitMs]) : une file d'inférences qui s'allonge se voit avant de se payer.
 * [runMeasured] rend en plus l'attente de l'appel, que l'appelant logue quand elle
 * est non nulle — [run] garde sa signature historique.
 */
class SerialRunGuard(
    /** Horloge ms de l'attente du verrou ; injectable en test. */
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val state = Any()
    private var closed = false
    private var pendingRelease: (() -> Unit)? = null
    private val acquisitions = AtomicLong(0L)
    private val contended = AtomicLong(0L)
    private val waitTotal = AtomicLong(0L)
    private val waitMax = AtomicLong(0L)

    /** Acquisitions du verrou, refusées comprises (voir [run]). */
    val runs: Long get() = acquisitions.get()

    /** Acquisitions qui ont attendu plus de 0 ms (file réelle). */
    val contendedRuns: Long get() = contended.get()

    /** Attente cumulée du verrou, en ms. */
    val totalWaitMs: Long get() = waitTotal.get()

    /** Pire attente du verrou, en ms. */
    val maxWaitMs: Long get() = waitMax.get()

    /**
     * Exécute [block] seul (aucun chevauchement avec un autre `run()` ni avec
     * la libération) ; échec « engine closed » si `close()` est déjà passé.
     */
    suspend fun <T> run(block: () -> T): Result<T> =
        runMeasured(block).map { it.first }

    /**
     * Comme [run], plus l'attente du verrou en ms (0 = acquis aussitôt) : l'appelant
     * la logue quand elle est non nulle (verrou réellement contenté).
     */
    suspend fun <T> runMeasured(block: () -> T): Result<Pair<T, Long>> {
        val queuedAt = clockMs()
        mutex.withLock {
            val waited = (clockMs() - queuedAt).coerceAtLeast(0L)
            acquisitions.incrementAndGet()
            if (waited > 0L) {
                contended.incrementAndGet()
                waitTotal.addAndGet(waited)
                waitMax.accumulateAndGet(waited) { a, b -> maxOf(a, b) }
            }
            if (isClosed()) {
                return Result.failure(IllegalStateException("engine closed"))
            }
            try {
                return Result.success(block() to waited)
            } finally {
                drain()
            }
        }
    }

    /**
     * Marque fermé et libère via [release] : aussitôt si aucun run n'est en
     * vol, sinon à la fin du run en cours. Ne bloque jamais (pas d'attente du
     * run) : appelable depuis le fil principal. Le second appel est un no-op.
     */
    fun close(release: () -> Unit) {
        synchronized(state) {
            if (closed) {
                return
            }
            closed = true
            pendingRelease = release
        }
        if (mutex.tryLock()) {
            try {
                drain()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun isClosed(): Boolean {
        synchronized(state) {
            return closed
        }
    }

    /** Exécute la libération en attente, s'il y en a une (sous `mutex`). */
    private fun drain() {
        var release: (() -> Unit)? = null
        synchronized(state) {
            release = pendingRelease
            pendingRelease = null
        }
        if (release != null) {
            release()
        }
    }
}
