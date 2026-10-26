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