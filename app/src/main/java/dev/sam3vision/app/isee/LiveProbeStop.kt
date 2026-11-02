package dev.sam3vision.app.isee

import android.util.Log
import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.net.ReleaseOutcome
import dev.sam3vision.core.net.releaseSam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Arrêt net de la sonde SAM : à part (même raison que `ProbeWiring.kt`), car
 * `LiveProbe.kt` est au plafond des 500 lignes.
 *
 * Caméra coupée ou `ViewModel` détruit, ce n'est pas assez de ne plus envoyer de
 * frames : les WebSockets restés ouverts garderaient le conteneur Modal allumé
 * (facturé). Ici les sessions sont fermées et oubliées, puis le conteneur est
 * éteint aussitôt (`POST /release`, coût 0 — sans lui, 60 s de réduintage
 * facturées) ; la séance suivante rouvre des sessions neuves via `configure`,
 * et sa première frame réveille la carte.
 */
internal fun LiveProbe.stop() {
    stopLocal()
    releaseOnCameraOff(state.value.endpoint)
}

/**
 * Arrêt local seul, sans extinction : la mise en fond diffère le `/release`
 * (grâce [ReleaseGrace]) et la destruction l'a déjà envoyé (`onDestroy`).
 */
internal fun LiveProbe.stopLocal() {
    resetPipeline()
    wifiLock.release() // fin du direct (caméra coupée, fond, onCleared) : la radio redort.
    closeSessions()
    link.reset()
    LiveLatencyLog.resetFailures()
    clear()
}

/**
 * Ferme la session du direct et l'oublie : la réponse en vol est révoquée par `close`,
 * les frames suivantes sont refusées (`wantsFrame` sans session répond non). Idempotent.
 *
 * Une seule fermeture depuis FIX-19 : `coldSession` lit `session` (session unique,
 * FIX-18), le second `close()` d'autrefois fermait deux fois le même transport.
 */
internal fun LiveProbe.closeSessions() {
    session?.close()
    session = null
}

/**
 * Mise en fond (grâce [ReleaseGrace] expirée) : éteint le conteneur GPU, en tâche
 * de fond (l'appelant reste synchrone). Même `releaseSam` que la fermeture d'app
 * (`AppDiagnostics`), endpoint vide : rien à éteindre.
 */
internal fun releaseOnCameraOff(endpoint: String) {
    if (endpoint.isBlank()) return
    stopReleaseScope.launch {
        val outcome = releaseOnStop(endpoint.trim())
        Log.i(
            "sam3vision",
            "extinction GPU (fond sans retour, grâce expirée) -> $endpoint : " +
                (outcome.getOrNull()?.let { "code=${it.code} ok=${it.released} corps=${it.body}" }
                    ?: "échec ${outcome.exceptionOrNull()?.message}"),
        )
    }
}

/**
 * Envoi réel de `POST /release`, remplaçable par les tests (même motif d'injection
 * que `clientFor` : le défaut fait le vrai travail, les tests branchent un faux).
 */
internal var releaseOnStop: suspend (String) -> Result<ReleaseOutcome> = { endpoint ->
    releaseSam(endpoint)
}

/** Portée dédiée : survit à la sonde le temps de la requête (comme `AppDiagnostics`). */
private val stopReleaseScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
