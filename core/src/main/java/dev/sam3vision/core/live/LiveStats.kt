package dev.sam3vision.core.live

/**
 * Statistiques glissantes d'un envoi du direct : moyenne, p50 et p95 sur les [maxSamples]
 * dernières mesures (20 par défaut), les plus anciennes sortant de la fenêtre.
 *
 * Attention : ceci suit la latence **d'un envoi** (file + aller-retour), pas la latence
 * perçue capteur → pixel, que mesure [LatencyProfile].
 *
 * Adaptateur de [RollingStat] : l'anneau de `long` et les percentiles n'existent qu'une
 * fois ([RollingStat]/[Percentiles]) ; les mesures négatives sont ramenées à zéro.
 * Sûre entre threads (enregistrement depuis le thread IO, lecture depuis l'UI).
 */
class LiveStats(maxSamples: Int = RollingStat.DEFAULT_WINDOW) {

    private val stat = RollingStat(maxSamples)

    /** Ajoute une mesure de latence (en ms), bornée à zéro. */
    fun record(latencyMs: Long) = stat.record(latencyMs.coerceAtLeast(0L))

    /** Nombre de mesures valides dans la fenêtre. */
    val samples: Int get() = stat.samples

    /** Moyenne entière de la fenêtre, 0 si aucune mesure. */
    val averageMs: Long get() = stat.average

    /** p50 : la médiane de la fenêtre, 0 si aucune mesure. */
    val p50Ms: Long get() = stat.percentile(Percentiles.P50)

    /**
     * p95 par rang le plus proche : sur la fenêtre triée, l'élément d'indice
     * `ceil(0.95 × n) - 1` (19e des 20 derniers échantillons). 0 si aucune mesure.
     */
    val p95Ms: Long get() = stat.percentile(Percentiles.P95)

    /** Oublie les mesures de la séance précédente. */
    fun reset() = stat.reset()
}

/**
 * Statistiques glissantes de l'écart global-boîte V1 (en px) : moyenne et p95 sur les
 * [maxSamples] dernières frames, les plus anciennes sortant de la fenêtre.
 *
 * Adaptateur de [RollingStat] en virgule fixe (millièmes de px) : l'anneau et les
 * percentiles n'existent qu'une fois ; les écarts négatifs sont ramenés à zéro, les
 * mesures non finies jetées. Sûre entre threads (enregistrement depuis le thread IO,
 * lecture depuis l'UI).
 */
class MotionGapStats(maxSamples: Int = RollingStat.DEFAULT_WINDOW) {

    private val stat = RollingStat(maxSamples)

    /** Ajoute un écart (en px), borné à zéro ; ignore les mesures non finies. */
    fun record(gapPx: Float) {
        if (!gapPx.isFinite()) return
        stat.record((gapPx.coerceAtLeast(0f) * SCALE).toLong())
    }

    /** Nombre de mesures valides dans la fenêtre. */
    val samples: Int get() = stat.samples

    /** Moyenne de la fenêtre (px), 0 si aucune mesure. */
    val averagePx: Float get() = stat.average / SCALE

    /** p95 par rang le plus proche (px), 0 si aucune mesure. */
    val p95Px: Float get() = stat.percentile(Percentiles.P95) / SCALE

    /** Oublie les mesures de la séance précédente. */
    fun reset() = stat.reset()

    companion object {
        /** Virgule fixe : millièmes de px dans l'anneau de `long`. */
        private const val SCALE = 1000f
    }
}
