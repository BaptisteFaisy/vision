package dev.sam3vision.core.live

import java.util.Arrays

/**
 * Fenêtre glissante de mesures entières (20 par défaut) : moyenne et percentiles par rang.
 *
 * Anneau de `long` bruts, et non une `ArrayDeque<Long>` : chaque `record` **boxait** sa
 * mesure et chaque lecture triait une copie de la fenêtre — un `ArrayList` et vingt `Long`
 * boxés par frame publiée (mesuré au banc JVM, [LiveFrameBudgetTest] : 4,1 → 0,4 µs et
 * 167 → 0 octet par frame). Le tri se fait dans un tampon réutilisé, la fenêtre elle-même
 * n'est jamais triée.
 *
 * Implémentation **unique** de cette logique : [LiveStats] (latence d'un envoi) et
 * [LatencyProfile] (un étage par fenêtre) s'y appuient. Sûre entre threads (écriture depuis
 * le thread IO, lecture depuis l'UI).
 */
class RollingStat(private val maxSamples: Int = DEFAULT_WINDOW) {

    /** Capacité de la fenêtre : une fenêtre vide n'aurait aucun sens (0 → 1). */
    private val size = maxSamples.coerceAtLeast(1)

    /** Fenêtre circulaire : [count] mesures valides, la plus ancienne en [head]. */
    private val window = LongArray(size)
    private var head = 0
    private var count = 0
    private var sum = 0L

    /** Copie de travail des percentiles : la fenêtre n'est jamais réordonnée. */
    private val scratch = LongArray(size)

    /** Ajoute une mesure à la fenêtre ; les plus anciennes sortent au-delà de [size]. */
    @Synchronized
    fun record(value: Long) {
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

    /** Nombre de mesures valides dans la fenêtre. */
    val samples: Int get() = synchronized(this) { count }

    /** Moyenne entière de la fenêtre, 0 si elle est vide. */
    val average: Long get() = synchronized(this) { if (count == 0) 0L else sum / count }

    /** Percentile [p] (en %) par rang le plus proche, 0 si la fenêtre est vide. */
    @Synchronized
    fun percentile(p: Double): Long {
        if (count == 0) return 0L
        var i = 0
        while (i < count) {
            scratch[i] = window[(head + i) % size]
            i++
        }
        Arrays.sort(scratch, 0, count)
        return Percentiles.of(scratch, count, p)
    }

    /** Oublie toutes les mesures (nouvelle séance, sortie d'écran). */
    @Synchronized
    fun reset() {
        window.fill(0L)
        head = 0
        count = 0
        sum = 0L
    }

    companion object {
        /** Fenêtre par défaut : vingt mesures, la même que celle du direct. */
        const val DEFAULT_WINDOW = 20
    }
}