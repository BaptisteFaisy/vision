package dev.sam3vision.app.live

/**
 * Ips du direct, calculés sur l'**intervalle réel** entre deux frames publiées — pas sur
 * la cadence demandée : ce que l'utilisateur voit, c'est le rythme effectif.
 *
 * Un intervalle nul ou négatif garde l'affichage précédent (deux frames dans la même
 * milliseconde ne valent pas 1000 ips) ; un trou au-delà de [MAX_GAP_MS] affiche **0**
 * (veille, réseau mort) : un vrai compteur ne masque pas les trous derrière un chiffre périmé.
 *
 * Logique pure (aucun Android) : testée en JVM.
 */
class LiveFpsMeter(private val maxGapMs: Long = MAX_GAP_MS) {

    /** Instant de la dernière frame publiée ; `0` = aucune mesure encore. */
    var lastFrameAt: Long = 0L
        private set

    /** Écart avec la frame précédente, `0` pour la première ; pose l'instant courant. */
    fun gapAt(nowMs: Long): Long {
        val gap = if (lastFrameAt == 0L) 0L else nowMs - lastFrameAt
        lastFrameAt = nowMs
        return gap
    }

    /** Oublie la mesure : nouvelle séance, la première frame ne compte pas d'intervalle. */
    fun reset() {
        lastFrameAt = 0L
    }

    companion object {
        /** Au-delà, le trou n'est plus du direct : veille, pause, réseau mort. */
        const val MAX_GAP_MS = 20_000L

        /** Ips pour un intervalle donné ; un trou vaut 0, jamais l'ancienne valeur. */
        fun fpsFor(gapMs: Long, previous: Float, maxGapMs: Long = MAX_GAP_MS): Float = when {
            gapMs < 1L -> previous // même milliseconde : pas de mesure, on garde l'affichage
            gapMs > maxGapMs -> 0f // trou (veille, réseau mort) : 0, pas un chiffre périmé
            else -> 1000f / gapMs
        }
    }
}