package dev.sam3vision.app.live

/**
 * Ips du direct, calculés sur l'**intervalle réel** entre deux frames publiées — pas sur
 * la cadence demandée : ce que l'utilisateur voit, c'est le rythme effectif.
 *
 * Un intervalle hors de `1..[MAX_GAP_MS]` est **rejeté** et l'affichage garde la valeur
 * précédente : deux frames dans la même milliseconde, comme un trou de 30 s (écran en
 * veille, réseau mort), produiraient un chiffre inventé (1000 ips ou 0,03 ips).
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

        /** Ips pour un intervalle donné, ou [previous] si l'intervalle est rejeté. */
        fun fpsFor(gapMs: Long, previous: Float, maxGapMs: Long = MAX_GAP_MS): Float =
            if (gapMs in 1..maxGapMs) 1000f / gapMs else previous
    }
}