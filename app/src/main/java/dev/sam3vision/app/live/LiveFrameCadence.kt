package dev.sam3vision.app.live

import dev.sam3vision.core.live.FramePipeline

/**
 * Cadence du direct : **une frame en vol maximum** et un intervalle adaptatif (déduit de
 * la latence moyenne, voir [FramePipeline.intervalMsAdaptive]). Une frame proposée trop tôt
 * est jetée sans attendre — sinon le lien montant sature et *toutes* les frames
 * vieillissent, y compris celles qui partent.
 *
 * Extrait de `LiveViewModel` pour être testable seul : la décision est une fonction du
 * temps, de l'occupation du pipeline et de sa cadence. Elle vaut pour les deux transports
 * du direct (`/live` en WebSocket et le tracker par sessions).
 */
class LiveFrameCadence {

    /** Instant du prochain créneau ; `0` = immédiat. */
    private var nextTickAt = 0L

    /** Nouvelle séance : le prochain créneau est immédiat. */
    fun reset() {
        nextTickAt = 0L
    }

    /**
     * Vrai si la frame peut partir maintenant. Sinon rien ne change — la frame est jetée.
     * Quand elle part, l'échéance suivante est posée à `now + intervalMsAdaptive()`.
     */
    fun admits(pipeline: FramePipeline?, nowMs: Long): Boolean {
        val active = pipeline ?: return false
        if (active.inFlight || nowMs < nextTickAt) return false
        nextTickAt = nowMs + active.intervalMsAdaptive()
        return true
    }
}