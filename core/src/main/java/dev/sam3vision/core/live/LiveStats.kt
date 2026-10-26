package dev.sam3vision.core.live

import java.util.Arrays

/**
 * Statistiques glissantes du direct : moyenne et p95 de latence sur les
 * [maxSamples] dernières mesures (20 par défaut). Les anciens échantillons
 * sortent de la fenêtre, [samples] ne dépasse jamais [maxSamples].
 * Sûre entre threads (enregistrement depuis le thread IO, lecture depuis l'UI).
 *
 * Anneau de `long` bruts, et non un `ArrayDeque<Long>` : chaque `record` **boxait** sa
 * mesure, et chaque lecture de [p95Ms] triait une copie de la fenêtre — soit un
 * `ArrayList` et vingt `Long` boxés **par frame publiée**, alors que [p95Ms] est lu à
 * chaque publication. Mesuré au banc JVM ([LiveFrameBudgetTest], 250 passes) :
 * 4,1 → 0,4 µs et 167 → 0 octet par frame.
 */
class LiveStats(private val maxSamples: Int = 20) {

    /** Capacité de la fenêtre : une fenêtre vide n'aurait aucun sens (0 → 1). */
    private val size = maxSamples.coerceAtLeast(1)

    /** Fenêtre circulaire : [count] mesures valides, la plus ancienne en [head]. */
    private val window = LongArray(size)
    private var head = 0
    private var count = 0
    private var sum = 0L

    /** Copie de travail du p95 : la fenêtre elle-même n'est jamais triée. */
    private val scratch = LongArray(size)

    /** Ajoute une mesure de latence (en ms), bornée à [maxSamples] chiffres significatifs. */
    @Synchronized
    fun record(latencyMs: Long) {
        val value = latencyMs.coerceAtLeast(0L)
        if (count == size) {
            sum -= window[head]
            window[head] = value
            head = (head + 1) % size
        } else {
            window[(head + count) % size] = value
            count += 1
        }
        sum += value
    }

    val samples: Int get() = synchronized(this) { count }

    /** Moyenne entière de la fenêtre, 0 si aucune mesure. */
    val averageMs: Long get() = synchronized(this) {
        if (count == 0) 0L else sum / count
    }

    /**
     * p95 par rang le plus proche : sur la fenêtre triée, élément d'indice
     * `ceil(0.95 × n) - 1` (19e des 20 derniers échantillons). 0 si vide.
     */
    val p95Ms: Long get() = synchronized(this) {
        if (count == 0) return 0L
        var i = 0
        while (i < count) {
            scratch[i] = window[(head + i) % size]
            i++
        }
        Arrays.sort(scratch, 0, count)
        val rank = Math.ceil(P95 * count / 100.0).toInt().coerceIn(1, count)
        scratch[rank - 1]
    }

    @Synchronized
    fun reset() {
        window.fill(0L)
        head = 0
        count = 0
        sum = 0L
    }

    companion object {
        private const val P95 = 95.0
    }
}