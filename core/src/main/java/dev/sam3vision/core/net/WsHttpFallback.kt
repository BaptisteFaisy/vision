package dev.sam3vision.core.net

/**
 * Re-sonde du WebSocket : une frame sur [HTTP_PROBE_EVERY] retente le socket quand le
 * repli HTTP colle (au pas du Direct, ~2 ips, un essai toutes les ~5 s). Assez espacé
 * pour ne pas payer un handshake mort à chaque frame, assez fréquent pour revenir au
 * WS dès qu'il revit — et le backoff du lien ([WsLink]) espace de toute façon deux
 * tentatives rapprochées.
 */
internal const val HTTP_PROBE_EVERY = 10

/**
 * Hystérésis anti-oscillation du repli HTTP, au **compteur de frames** (déterministe,
 * sans horloge) : le repli colle après [HTTP_STICK_AFTER] échec(s) WS de suite, et ne
 * se décolle qu'après [HTTP_UNSTICK_AFTER] succès de suite. Sans ça, sur un socket
 * flappant, chaque re-sonde réussie (1 frame sur 10) faisait clignoter la jambe
 * HTTP↔WS ; avec 2 succès exigés, un succès isolé ne décolle plus — il faut une vraie
 * reprise. Un échec annule les succès en cours de confirmation, et réciproquement.
 */
internal const val HTTP_STICK_AFTER = 1
internal const val HTTP_UNSTICK_AFTER = 2

/** Signal interne : le WS ne peut pas servir la frame — elle part en POST /live. */
internal object FallbackToHttp : Exception("WS indisponible : repli POST /live")

/**
 * Repli HTTP du direct : **collant**, avec re-sonde WS périodique — même esprit que le
 * repli collant binaire→texte (`binaryRefused`).
 *
 * Politique (au **compteur de frames**, pas au délai : déterministe, sans horloge) :
 * [stickAfter] échecs WS de suite ([onWsFailed]) collent le repli — les frames partent
 * en POST /live, aucun handshake mort par frame. Une frame sur [probeEvery] retente le
 * WS ([routeWs]) ; [unstickAfter] succès de suite ([onWsServed]) le décollent, retour
 * au socket. Non thread-safe : [WsLiveClient] l'appelle sous son propre verrou.
 *
 * @param onChange annonce les vraies bascules (`true` = collé, `false` = décollé),
 * shape identique à [WsLeg] : appel synchrone sous le verrou du client — rapide, sans
 * E-S, sans réentrance (ne rappelle pas la politique). Silencieux à [reset].
 */
class WsHttpFallback(
    private val probeEvery: Int = HTTP_PROBE_EVERY,
    private val stickAfter: Int = HTTP_STICK_AFTER,
    private val unstickAfter: Int = HTTP_UNSTICK_AFTER,
    private val onChange: ((Boolean) -> Unit)? = null,
) {
    /** Vrai quand les frames partent en POST /live — l'écran dit « HTTP ». */
    var active: Boolean = false
        private set

    /** Frames confiées au HTTP depuis le dernier essai WS (re-sonde à [probeEvery]). */
    private var httpServed = 0

    /** Succès WS de suite en cours de confirmation (décolle à [unstickAfter]). */
    private var wsServed = 0

    /** Échecs WS de suite hors repli (colle à [stickAfter]). */
    private var wsFailed = 0

    /** Bascules cumulées vers HTTP depuis la session (observabilité, [snapshot]). */
    var stickCount: Int = 0
        private set

    /** Bascules cumulées vers WS depuis la session (observabilité, [snapshot]). */
    var unstickCount: Int = 0
        private set

    /** Re-sondes WS tentées depuis la session (observabilité, [snapshot]). */
    var probeCount: Int = 0
        private set

    /**
     * Route d'une frame : `false` = POST direct (repli collant), `true` = tenter le WS
     * (jamais replié, ou re-sonde due — le compteur repart alors de zéro).
     */
    fun routeWs(): Boolean {
        if (!active) return true
        httpServed += 1
        if (httpServed < probeEvery.coerceAtLeast(1)) return false
        httpServed = 0
        probeCount += 1
        return true
    }

    /**
     * Le WS a servi une frame : hors repli, ça casse juste la série d'échecs ; en
     * repli, [unstickAfter] succès de suite décollent, retour au socket.
     */
    fun onWsServed() {
        wsFailed = 0
        if (!active) return
        wsServed += 1
        if (wsServed < unstickAfter.coerceAtLeast(1)) return
        wsServed = 0
        httpServed = 0
        unstickCount += 1
        setActive(false)
    }

    /**
     * Le WS n'a pas servi la frame : en repli elle part en HTTP (et annule la
     * confirmation en cours) ; hors repli, [stickAfter] échecs de suite collent.
     */
    fun onWsFailed() {
        wsServed = 0
        if (active) {
            httpServed = 1
            return
        }
        wsFailed += 1
        if (wsFailed < stickAfter.coerceAtLeast(1)) return
        wsFailed = 0
        httpServed = 1
        stickCount += 1
        setActive(true)
    }

    /** Fin de session : ni repli fantôme, ni compteur — les cumulés aussi. */
    fun reset() {
        active = false
        httpServed = 0
        wsServed = 0
        wsFailed = 0
        stickCount = 0
        unstickCount = 0
        probeCount = 0
    }

    /** Vue figée : état, séries en cours et cumulés ([HttpFallbackSnapshot]). */
    fun snapshot(): HttpFallbackSnapshot =
        HttpFallbackSnapshot(active, httpServed, wsServed, wsFailed, stickCount, unstickCount, probeCount)

    /** Bascule annoncée une seule fois ([onChange]) — les appels redondants se taisent. */
    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        onChange?.invoke(value)
    }
}
