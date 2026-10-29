package dev.sam3vision.app

import android.os.SystemClock
import android.util.Log
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.HttpLiveClient
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeoutException

/**
 * Réveil explicite de la GPU sur intention caméra ferme (ordre user 2026-10-29) :
 * GPU éteinte par défaut, et dès que la caméra se lie (sonde iSee), que le Direct
 * démarre ou que l'écran Capture s'affiche, un `/health` patient part aussitôt —
 * le boot Modal (~2-30 s) recouvre la liaison caméra et la première frame au lieu
 * de les suivre. Le même appel réchauffe le pool TLS du host.
 *
 * À distinguer de la sonde supprimée le 2026-10-26 (`LiveWakeGuard`) : celle-là
 * réveillait sans analyse ; ici l'analyse suit dans la seconde (frames en route).
 * Un seul réveil en vol par endpoint ; jamais périodique, jamais sans caméra.
 */
object GpuWake {

    private val flying = mutableSetOf<String>()

    /**
     * Réveille [endpoint] via [health] (`/health` patient, 180 s, par défaut).
     * Renvoie `false` sans rien faire si l'endpoint est vide ou un réveil y est
     * déjà en vol. [onResult] reçoit l'issue (moniteur GPU…) ; la durée est loggée.
     */
    suspend fun wake(
        endpoint: String,
        health: suspend (String) -> Result<Boolean> = { HttpLiveClient(it).health().map { h -> h.ok } },
        onResult: (Result<Boolean>) -> Unit = {},
        clock: () -> Long = SystemClock::elapsedRealtime,
        log: (String) -> Unit = { line -> Log.i("sam3vision", line) },
    ): Boolean {
        val clean = endpoint.trim()
        if (clean.isEmpty() || !acquire(clean)) return false
        val startedAt = clock()
        try {
            val outcome = health(clean)
            onResult(outcome)
            log(wakeLine(clean, outcome.getOrNull() == true, clock() - startedAt, outcome.exceptionOrNull()))
        } finally {
            release(clean)
        }
        return true
    }

    /** `réveil GPU (https://…) : 4.2 s, carte chaude` — ou `…, échec 404` (point décimal fixe). */
    internal fun wakeLine(endpoint: String, ok: Boolean, ms: Long, error: Throwable? = null): String =
        "réveil GPU ($endpoint) : ${"%.1f".format(Locale.US, ms / 1000.0)} s, " +
            if (ok) "carte chaude" else "échec ${wakeMotif(error)}"

    /**
     * Motif court d'un réveil raté : le code HTTP (`404`, app arrêtée ou route
     * manquante), `délai dépassé`, sinon la première ligne du message — pour
     * savoir pourquoi sans rouvrir l'enquête.
     */
    internal fun wakeMotif(error: Throwable?): String = when (error) {
        null -> "réponse négative"
        is ApiError.BackendStopped -> "404"
        is ApiError.Http -> error.code.toString()
        is ApiError.Timeout,
        is InterruptedIOException,
        is TimeoutException -> "délai dépassé"
        else -> error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            .ifEmpty { error.javaClass.simpleName }
            .take(80)
    }

    private fun acquire(endpoint: String): Boolean = synchronized(flying) { flying.add(endpoint) }

    private fun release(endpoint: String) {
        synchronized(flying) { flying.remove(endpoint) }
    }
}
