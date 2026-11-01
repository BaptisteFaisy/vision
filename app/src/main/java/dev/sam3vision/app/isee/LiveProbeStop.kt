package dev.sam3vision.app.isee

import android.util.Log
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
    resetPipeline()
    wifiLock.release() // fin du direct (caméra coupée, fond, onCleared) : la radio redort.
    closeSessions()
    releaseOnCameraOff(state.value.endpoint)
    clear()
}

/**
 * Ferme les deux sessions (normale + froide) et les oublie : la réponse en vol est
 * révoquée par `close`, les frames suivantes sont refusées (`wantsFrame` sans
 * session répond non). Idempotent : sans session, ne fait rien.
 */
internal fun LiveProbe.closeSessions() {
    session?.close()
    session = null
    coldSession?.close()
    coldSession = null
}

/**
 * Caméra coupée : éteint le conteneur GPU aussitôt, en tâche de fond (`stop()`
 * reste synchrone pour ses appelants). Même `releaseSam` que la fermeture d'app
 * (`AppDiagnostics`), endpoint vide : rien à éteindre.
 */
internal fun releaseOnCameraOff(endpoint: String) {
    if (endpoint.isBlank()) return
    stopReleaseScope.launch {
        val outcome = releaseOnStop(endpoint.trim())
        Log.i(
            "sam3vision",
            "extinction GPU (caméra coupée) -> $endpoint : " +
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
