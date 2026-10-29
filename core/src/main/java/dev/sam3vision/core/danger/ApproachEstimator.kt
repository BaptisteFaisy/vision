package dev.sam3vision.core.danger

/**
 * Lecture d'approche d'une piste : vient-elle vers le téléphone ?
 *
 * [approaching] = la boîte grossit vite ([growthPerSec] ≥ seuil) ET elle est
 * centrée ([centered]) — quelqu'un qui traverse le bord du champ ne compte pas.
 * [ttcSec] = temps-avant-contact optique (`taille / taux`, secondes), présent
 * seulement quand ça s'approche : à vitesse constante, la taille apparente
 * suit `s(t) ∝ 1 / (ttc − t)`, donc `ttc ≈ s / (ds/dt)`.
 */
data class ApproachReading(
    val trackId: Int,
    val approaching: Boolean,
    val centered: Boolean,
    val growthPerSec: Float,
    val ttcSec: Float?,
)

/**
 * Lit l'approche sur l'historique des tailles d'une piste : on garde les
 * (instant, taille) de la fenêtre ([windowMs]), le taux vient de la plus
 * vieille à la plus récente. Pur Kotlin, sans Android.
 *
 * La taille est `√aire` normalisée : elle grandit linéairement quand la
 * personne avance à vitesse constante (l'aire, elle, grandit au carré).
 */
class ApproachEstimator(
    /** Fenêtre d'historique : 1,5 s de marche, assez pour un taux stable. */
    private val windowMs: Long = 1_500L,
    /** Taux minimal : +3 % de taille normalisée par seconde. */
    private val minGrowthPerSec: Float = 0.03f,
    /** Demi-largeur du couloir central : |cx − 0,5| ≤ 0,25. */
    private val centerMargin: Float = 0.25f,
    /** Écart minimal oldest→newest : sous 300 ms, pas de taux fiable. */
    private val minDtMs: Long = 300L,
) {
    private data class Sample(val atMs: Long, val size: Float)

    private val history = mutableMapOf<Int, ArrayDeque<Sample>>()

    /**
     * Ajoute la piste vue à [nowMs], rend sa lecture d'approche. Une piste vue
     * pour la première fois n'a pas de taux : `approaching = false`.
     */
    fun update(track: DangerTrack, nowMs: Long): ApproachReading {
        val samples = history.getOrPut(track.id) { ArrayDeque() }
        samples.addLast(Sample(nowMs, sizeOf(track)))
        prune(samples, nowMs)
        val rate = growthRate(samples)
        val centered = kotlin.math.abs(track.centerX - 0.5f) <= centerMargin
        val approaching = rate != null && rate >= minGrowthPerSec && centered
        val ttc = if (approaching) sizeOf(track) / rate!! else null
        return ApproachReading(track.id, approaching, centered, rate ?: 0f, ttc)
    }

    /** Oublie les pistes qui ne sont plus suivies (sorties du champ). */
    fun retainAlive(ids: Set<Int>) {
        history.keys.retainAll(ids)
    }

    /** Oublie tout (nouvelle séance). */
    fun reset() {
        history.clear()
    }

    /** Taille apparente : racine de l'aire normalisée. */
    private fun sizeOf(track: DangerTrack): Float = kotlin.math.sqrt(track.area)

    /** Taux oldest→newest, `null` si la fenêtre est trop courte. */
    private fun growthRate(samples: ArrayDeque<Sample>): Float? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val dtSec = (last.atMs - first.atMs) / 1000f
        if (samples.size < 2 || dtSec * 1000f < minDtMs) return null
        return (last.size - first.size) / dtSec
    }

    /** Ne garde que la fenêtre ([windowMs]) précédant [nowMs]. */
    private fun prune(samples: ArrayDeque<Sample>, nowMs: Long) {
        while (samples.size > 1 && nowMs - samples.first().atMs > windowMs) {
            samples.removeFirst()
        }
    }
}
