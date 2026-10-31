package dev.sam3vision.core.keyframe

import kotlin.math.ceil

/**
 * Seau à jetons : [ratePerSec] jetons reconstitués par seconde, [burst] au maximum en réserve,
 * **plus un jeton d'urgence** pris à découvert.
 *
 * Pourquoi un découvert : une coupure de scène ne peut pas rater la borne de débit d'un cheveu —
 * elle doit partir **tout de suite**. Un seul jeton peut être emprunté (jamais deux à la fois), et
 * la reconstitution le rembourse avant tout nouvel octroi. Sur toute fenêtre de 1 s, la borne
 * reste donc `rafale + débit + 1` : le coût GPU garde un plafond démontrable.
 *
 * Toutes les méthodes sont sûres entre threads (thread caméra + thread IO). Une horloge qui
 * recule ne rend rien — au pire elle retarde la reconstitution, jamais elle n'en invente.
 */
class RequestBudget(ratePerSec: Float, burst: Int) {

    private val rate = ratePerSec
    private val capacity = burst
    private var tokens = burst.toFloat()
    private var lastRefillMs = 0L
    private var debt = 0

    init {
        require(rate > 0f) { "ratePerSec must be > 0" }
        require(burst >= 1) { "burst must be >= 1" }
    }

    /** Un jeton est-il disponible pour une demande ordinaire ? (jamais à découvert) */
    @Synchronized
    fun allow(nowMs: Long): Boolean {
        refill(nowMs)
        if (tokens < 1f) return false
        tokens -= 1f
        return true
    }

    /** Demande urgente : emprunte **un** jeton à découvert si la réserve est vide. */
    @Synchronized
    fun allowUrgent(nowMs: Long): Boolean {
        refill(nowMs)
        if (tokens >= 1f) {
            tokens -= 1f
            return true
        }
        if (debt > 0) return false
        debt = 1
        return true
    }

    /** Millisecondes avant le prochain jeton (`0` si un jeton attend déjà). */
    @Synchronized
    fun msUntilToken(nowMs: Long): Long {
        refill(nowMs)
        if (tokens >= 1f) return 0L
        val missingMs = (1f - tokens) * 1000.0 / rate
        return ceil(missingMs).toLong().coerceAtLeast(1L)
    }

    /** Repart avec un seau plein (nouvelle session de direct). */
    @Synchronized
    fun reset(nowMs: Long) {
        tokens = capacity.toFloat()
        lastRefillMs = nowMs
        debt = 0
    }

    /** Reconstitution proportionnelle au temps écoulé, puis remboursement du découvert. */
    private fun refill(nowMs: Long) {
        val elapsedMs = nowMs - lastRefillMs
        if (elapsedMs <= 0L) return
        lastRefillMs = nowMs
        tokens = (tokens + elapsedMs * rate / 1000f).coerceAtMost(capacity.toFloat())
        while (debt > 0 && tokens >= 1f) {
            tokens -= 1f
            debt -= 1
        }
    }
}