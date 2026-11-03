package dev.sam3vision.core.net

/**
 * Re-sonde du WebSocket : une frame sur [HTTP_PROBE_EVERY] retente le socket quand le
 * repli HTTP colle (au pas du Direct, ~2 ips, un essai toutes les ~5 s). Assez espacé
 * pour ne pas payer un handshake mort à chaque frame, assez fréquent pour revenir au
 * WS dès qu'il revit — et le backoff du lien ([WsLink]) espace de toute façon deux
 * tentatives rapprochées.
 */
internal const val HTTP_PROBE_EVERY = 10

/** Signal interne : le WS ne peut pas servir la frame — elle part en POST /live. */
internal object FallbackToHttp : Exception("WS indisponible : repli POST /live")

/**
 * Repli HTTP du direct : **collant**, avec re-sonde WS périodique — même esprit que le
 * repli collant binaire→texte (`binaryRefused`).
 *
 * Politique (au **compteur de frames**, pas au délai : déterministe, sans horloge) : la
 * première frame que le WS ne peut pas servir ([onWsFailed]) part en POST /live, et les
 * suivantes aussi — aucun handshake mort par frame. Une frame sur [HTTP_PROBE_EVERY]
 * retente le WS ([routeWs]) ; dès qu'il sert à nouveau ([onWsServed]), le repli se
 * décolle. Non thread-safe : [WsLiveClient] l'appelle sous son propre verrou.
 */
class WsHttpFallback(private val probeEvery: Int = HTTP_PROBE_EVERY) {
    /** Vrai quand les frames partent en POST /live — l'écran dit « HTTP ». */
    var active: Boolean = false
        private set

    /** Frames confiées au HTTP depuis le dernier essai WS (re-sonde à [probeEvery]). */
    private var httpServed = 0

    /**
     * Route d'une frame : `false` = POST direct (repli collant), `true` = tenter le WS
     * (jamais replié, ou re-sonde due — le compteur repart alors de zéro).
     */
    fun routeWs(): Boolean {
        if (!active) return true
        httpServed += 1
        if (httpServed < probeEvery.coerceAtLeast(1)) return false
        httpServed = 0
        return true
    }

    /** Le WS a servi une frame : le repli se décolle, retour au socket. */
    fun onWsServed() {
        active = false
        httpServed = 0
    }

    /** Le WS n'a pas servi la frame : elle part en HTTP, et les suivantes aussi. */
    fun onWsFailed() {
        active = true
        httpServed = 1
    }

    /** Fin de session : ni repli fantôme, ni compteur. */
    fun reset() {
        active = false
        httpServed = 0
    }
}
