package dev.sam3vision.core.local

/**
 * Réglages du **calque local** (lane L13, agent D/u13) : entre deux keyframes du serveur,
 * le masque du dernier keyframe est redessiné sur le téléphone avec la matrice de
 * mouvement estimée — c'est le seul chemin sous 100 ms, le réseau ne descendant pas là.
 *
 * Tous les chiffres viennent du rapport `u13` (vague 2) et sont figés ici, pas recalculés :
 * 30 ips visés, 10 ips plancher quand la peinture dépasse son budget, keyframe de plus de
 * 1 200 ms périmé (le masque n'a plus de sens), confiance minimale 0,35, budget de peinture
 * 4 ms par frame, 3 échecs d'estimateur consécutifs avant d'abandonner.
 *
 * [enabled] est le drapeau effectif (réglage utilisateur **et** drapeau de build, voir
 * `localOverlayFlag` côté app) : à `false`, [LocalOverlayEngine] refuse tout tick.
 */
data class LocalOverlayConfig(
    val enabled: Boolean = true,
    val targetFps: Int = 30,
    val minFps: Int = 10,
    val maxKeyframeAgeMs: Long = 1_200L,
    val minConfidence: Float = 0.35f,
    val paintBudgetMs: Long = 4L,
    val maxFailures: Int = 3,
) {

    /** Intervalle visé entre deux images propagées : 33 ms à 30 ips. */
    val targetIntervalMs: Long get() = intervalFor(targetFps)

    /** Intervalle le plus long admis (cadence plancher) : 100 ms à 10 ips. */
    val maxIntervalMs: Long get() = intervalFor(minFps).coerceAtLeast(targetIntervalMs)

    /** Budget de peinture en microsecondes : la mesure en vol ne perd pas le demi-ms. */
    val paintBudgetUs: Long get() = paintBudgetMs * 1_000L

    /** Cadence ramenée dans ses bornes : un réglage hors limites ne bloque pas le direct. */
    fun normalized(): LocalOverlayConfig {
        val target = targetFps.coerceIn(MIN_FPS, MAX_FPS)
        return copy(
            targetFps = target,
            minFps = minFps.coerceIn(MIN_FPS, target),
            maxKeyframeAgeMs = maxKeyframeAgeMs.coerceAtLeast(intervalFor(target)),
            minConfidence = minConfidence.coerceIn(0f, 1f),
            paintBudgetMs = paintBudgetMs.coerceAtLeast(1L),
            maxFailures = maxFailures.coerceAtLeast(1),
        )
    }

    companion object {
        /** Bornes de la cadence : sous 1 ips il n'y a plus de calque, au-delà de 60 on ment. */
        const val MIN_FPS = 1
        const val MAX_FPS = 60

        /** Intervalle d'une cadence en ips ; jamais 0 (division interdite côté appelant). */
        fun intervalFor(fps: Int): Long = (1_000L / fps.coerceAtLeast(MIN_FPS)).coerceAtLeast(1L)
    }
}