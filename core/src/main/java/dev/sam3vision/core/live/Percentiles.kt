package dev.sam3vision.core.live

/**
 * Percentiles par **rang le plus proche** sur un tableau déjà trié.
 *
 * Rang = `ceil(p/100 × n)`, borné à `[1, n]` : sur une fenêtre de vingt mesures, le p50 est
 * la 10e valeur, le p95 la 19e ; sur une mesure seule, c'est elle-même. Implémentation
 * **unique** : [RollingStat] s'en sert, [LiveStats] et [LatencyProfile] passent par lui —
 * aucune copie locale de ce calcul.
 *
 * Le p99 n'a de sens que sur une fenêtre large (le lien lunettes utilise 100 mesures :
 * le rang y est la 99e, pas le max).
 */
internal object Percentiles {

    /** Médiane : coupe la fenêtre en deux moitiés. */
    const val P50 = 50.0

    /** Queue haute : une mesure sur vingt la dépasse. */
    const val P95 = 95.0

    /** Queue extrême : une mesure sur cent la dépasse (fenêtre large exigée). */
    const val P99 = 99.0

    /**
     * Indice (base 0) du percentile [p] dans un tableau trié de [count] valeurs.
     * [count] doit être strictement positif : une fenêtre vide n'a pas de percentile.
     */
    fun rank(p: Double, count: Int): Int = Math.ceil(p / 100.0 * count).toInt().coerceIn(1, count) - 1

    /** Valeur du percentile [p] ; [sorted] doit être trié sur ses [count] premières cases. */
    fun of(sorted: LongArray, count: Int, p: Double): Long = sorted[rank(p, count)]
}