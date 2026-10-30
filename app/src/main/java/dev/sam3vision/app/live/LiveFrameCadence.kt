package dev.sam3vision.app.live

import dev.sam3vision.core.live.FramePipeline
import dev.sam3vision.core.live.LiveSession

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
 *
 * [wantsFrame] est la même admission, en lecture seule : la caméra l'appelle **avant**
 * d'encoder (motif `IseeCameraPane`), pour ne pas payer un JPEG qui serait jeté.
 */
class LiveFrameCadence {

    /**
     * Bras B de la cadence (réglage `continuousCadenceEnabled`, **adaptatif par
     * défaut**) : l'échéance suit la cadence continue ([LiveSession.intervalMsContinuous],
     * moitié de la latence) au lieu des paliers adaptatifs. Sans effet hors `LiveSession`
     * (le tracker garde ses 33 ms fixes) : le repli est l'adaptatif.
     */
    var continuous: Boolean = false

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

    /**
     * Vrai si une frame proposée maintenant partirait ([admits] sans l'effet de bord) :
     * la caméra économise l'encodage quand c'est faux. Sans pipeline, faux.
     */
    fun wantsFrame(pipeline: FramePipeline?, nowMs: Long): Boolean {
        val active = pipeline ?: return false
        return !active.inFlight && isDue(nowMs)
    }

    /** Vrai si l'échéance est passée (lecture seule : ne change rien). */
    fun isDue(nowMs: Long): Boolean = nowMs >= nextTickAt

    /** Une frame part à [atMs] : l'échéance suivante est posée (chemin avec réserve). */
    fun markSent(pipeline: FramePipeline, atMs: Long) {
        nextTickAt = atMs + intervalMs(pipeline)
    }

    /** Échéance du bras actif : continue si [continuous] et session `/live`, adaptative sinon. */
    private fun intervalMs(pipeline: FramePipeline): Long =
        if (continuous) (pipeline as? LiveSession)?.intervalMsContinuous() ?: pipeline.intervalMsAdaptive()
        else pipeline.intervalMsAdaptive()
}
