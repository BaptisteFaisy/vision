package dev.sam3vision.core.depth

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
 */
class SerialRunGuard {

    private val mutex = Mutex()
    private val state = Any()
    private var closed = false
    private var pendingRelease: (() -> Unit)? = null

    /**
     * Exécute [block] seul (aucun chevauchement avec un autre `run()` ni avec
     * la libération) ; échec « engine closed » si `close()` est déjà passé.
     */
    suspend fun <T> run(block: () -> T): Result<T> {
        mutex.withLock {
            if (isClosed()) {
                return Result.failure(IllegalStateException("engine closed"))
            }
            try {
                val value = block()
                return Result.success(value)
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
