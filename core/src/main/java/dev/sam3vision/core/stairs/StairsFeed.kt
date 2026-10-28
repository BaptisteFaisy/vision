package dev.sam3vision.core.stairs

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.mask.GrayMask

/**
 * Traitement escaliers d'une frame caméra, appelé à chaque tick du direct :
 * [luma] doit être un plan [LumaPlane] complet (256×103), sinon le signal est
 * vide (sûr : une frame inexploitable casse la série au lieu de confirmer).
 *
 * L'annonce confirmée reste affichée [HOLD_MS] (le cooldown voix, lui, vit
 * dans [StairWatcher]) : sans maintien, la phrase ne vivrait qu'un tick.
 * La voix n'est pas branchée ici (ni scope ni synthétiseur à ce niveau) —
 * [StairCue.phrase] est prête pour la synthèse existante.
 *
 * Pur JVM, sans OpenCV (doctrine `:core`).
 */
class StairsFeed(
    private val config: StairsConfig = StairsConfig(),
    private val watcher: StairWatcher = StairWatcher(),
    private val holdMs: Long = HOLD_MS,
) {

    /** Dernière phrase confirmée, affichée jusqu'à [shownUntilMs]. */
    private var shown: String = ""

    /** Fin d'affichage de [shown] (0 = rien à afficher). */
    private var shownUntilMs: Long = 0L

    /**
     * Ligne « escalier » à afficher après cette frame (`""` = rien) : détection
     * sur [luma], confirmation temporelle, maintien de la dernière annonce.
     */
    fun tick(luma: ByteArray?, nowMs: Long): String {
        val signal = if (luma != null && luma.size == LumaPlane.SIZE) {
            val mask = GrayMask(LumaPlane.WIDTH, LumaPlane.HEIGHT, luma)
            StairDetector.detect(mask, config)
        } else {
            StairSignal.none()
        }
        val cue = watcher.onSignal(signal, nowMs)
        if (cue != null) {
            shown = cue.phrase
            shownUntilMs = nowMs + holdMs
        } else if (nowMs >= shownUntilMs) {
            shown = ""
        }
        return shown
    }

    /** Oublie l'annonce et l'affichage (nouvelle séance de direct). */
    fun reset() {
        watcher.reset()
        shown = ""
        shownUntilMs = 0L
    }

    companion object {
        /** Durée d'affichage d'une annonce confirmée. */
        const val HOLD_MS = 3000L
    }
}
