package dev.sam3vision.app.live

import dev.sam3vision.core.live.FramePipeline

/**
 * Cadence d'envoi : **une frame en vol maximum** et un intervalle adaptatif (déduit de
 * la latence moyenne, voir [FramePipeline.intervalMsAdaptive]). Une frame proposée trop tôt
 * est jetée sans attendre — sinon le lien montant sature et *toutes* les frames
 * vieillissent, y compris celles qui partent.
 *
 * Extrait de `LiveViewModel` pour être testable seul : la décision est une fonction du
 * temps, de l'occupation du pipeline et de sa cadence. Elle vaut pour le tracker par
 * sessions **et** pour la sonde iSee (`LiveProbe`), qui garde sa réserve d'une frame
 * mais partage l'échéance : [admits] pour le chemin strict (créneau + cadence),
 * [isDue] + [markSent] pour le chemin avec réserve (la frame en attente avance
 * l'échéance sans repasser le garde). Le Direct `/live` ne passe plus par ici : il
 * envoie par la pompe, voir [LiveFrameDispatcher].
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
        if (active.inFlight || !isDue(nowMs)) return false
        markSent(active, nowMs)
        return true
    }

    /** Vrai si l'échéance est passée (lecture seule : ne change rien). */
    fun isDue(nowMs: Long): Boolean = nowMs >= nextTickAt

    /** Une frame part à [atMs] : l'échéance suivante est posée (chemin avec réserve). */
    fun markSent(pipeline: FramePipeline, atMs: Long) {
        nextTickAt = atMs + pipeline.intervalMsAdaptive()
    }
}