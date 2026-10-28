package dev.sam3vision.core.net

import dev.sam3vision.core.model.SegmentResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Présence de la GPU SAM, telle que le téléphone l'a **observée** : jamais devinée.
 * `Unknown` = rien vu encore ; `Checking` = une veille sonde `/health` ; `Up` = un
 * appel a réussi ; `Down` = le dernier appel a échoué, avec son motif.
 */
sealed interface GpuPresence {
    data object Unknown : GpuPresence
    data object Checking : GpuPresence
    data object Up : GpuPresence
    data class Down(val reason: String) : GpuPresence
}

/** Endpoint suivi et sa présence observée. Vide = aucun endpoint (rien à sonder). */
data class GpuStatus(
    val endpoint: String = "",
    val presence: GpuPresence = GpuPresence.Unknown,
)

/**
 * Détecte quand la GPU est branchée, sans jamais la tenir éveillée.
 *
 * Deux sources, et elles seulement :
 * - les vrais appels (photo, « Tester SAM ») via [noteSamResult]/[noteHealthResult] :
 *   un succès vaut `Up`, un échec vaut `Down` — zéro requête ajoutée ;
 * - [watch], qui ne sonde `/health` que si l'état est `Down` (une app stoppée répond
 *   vite sans rien réveiller) et s'arrête au premier succès, à la borne [maxProbes],
 *   à l'annulation ou au changement d'endpoint. Jamais de sonde sur `Up` (elle
 *   empêcherait l'extinction), sur `Unknown` (pas d'échec observé : pas de réveil
 *   surprise) ni sans endpoint.
 *
 * Le coût d'une détection est donc borné : tant que la carte est éteinte les sondes
 * sont gratuites, et quand on la branche la sonde suivante la réveille une fois
 * (cold start) puis la veille s'arrête — la carte se rendort à sa fenêtre.
 */
class GpuMonitor(
    private val samClient: (String) -> SamClient = { HttpSamClient(it) },
    private val maxProbes: Int = DEFAULT_MAX_PROBES,
    private val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val _status = MutableStateFlow(GpuStatus())
    val status: StateFlow<GpuStatus> = _status

    /**
     * Endpoint suivi (celui des réglages enregistrés). Un changement repart
     * d'`Unknown` : l'état observé d'une région ne vaut rien pour l'autre.
     */
    fun track(endpoint: String) {
        val clean = endpoint.trim()
        if (clean == _status.value.endpoint) return
        _status.value = GpuStatus(endpoint = clean)
    }

    /** Issue d'un vrai `/segment` : succès ⇒ `Up`, échec ⇒ `Down` (motif du client). */
    fun noteSamResult(endpoint: String, result: Result<SegmentResult>) {
        if (!accept(endpoint)) return
        _status.value = _status.value.copy(
            presence = if (result.isSuccess) {
                GpuPresence.Up
            } else {
                GpuPresence.Down(reasonOf(result.exceptionOrNull()))
            },
        )
    }

    /** Issue d'un vrai `/health` : `true` ⇒ `Up`, `false` ou échec ⇒ `Down`. */
    fun noteHealthResult(endpoint: String, result: Result<Boolean>) {
        if (!accept(endpoint)) return
        _status.value = _status.value.copy(
            presence = when {
                result.getOrNull() == true -> GpuPresence.Up
                result.isSuccess -> GpuPresence.Down(HEALTH_FALSE_REASON)
                else -> GpuPresence.Down(reasonOf(result.exceptionOrNull()))
            },
        )
    }

    /**
     * Veille bornée : sonde `/health` tant que l'état est `Down`, s'arrête au
     * premier succès. Annulable (portée d'écran) : annulée avant tout verdict,
     * elle restaure l'état précédent au lieu de rester coincée sur `Checking`.
     */
    suspend fun watch() {
        val tracked = _status.value.endpoint
        if (tracked.isBlank() || _status.value.presence !is GpuPresence.Down) return
        val previous = _status.value
        _status.value = previous.copy(presence = GpuPresence.Checking)
        try {
            repeat(maxProbes) { index ->
                if (_status.value.endpoint != tracked) return
                if (index > 0) sleeper(probeIntervalMs)
                if (_status.value.endpoint != tracked) return
                noteHealthResult(tracked, samClient(tracked).health())
                if (_status.value.presence is GpuPresence.Up) return
            }
        } finally {
            if (_status.value.presence is GpuPresence.Checking) _status.value = previous
        }
    }

    /** Une note ne vaut que pour l'endpoint suivi (jamais d'attribution croisée). */
    private fun accept(endpoint: String): Boolean {
        val tracked = _status.value.endpoint
        return tracked.isNotBlank() && endpoint.trim() == tracked
    }

    companion object {
        /** 12 sondes × 10 s : ~2 min de veille par visite d'écran, jamais plus. */
        const val DEFAULT_MAX_PROBES = 12
        const val DEFAULT_PROBE_INTERVAL_MS = 10_000L

        /** Motif d'un `/health` qui répond 2xx sans `ok` (même lecture que le client). */
        const val HEALTH_FALSE_REASON = "/health a répondu faux"
    }
}

/** Motif affichable d'un échec : le message du client, jamais vide ni muet. */
private fun reasonOf(error: Throwable?): String =
    error?.message?.takeIf { it.isNotBlank() } ?: "échec inconnu"
