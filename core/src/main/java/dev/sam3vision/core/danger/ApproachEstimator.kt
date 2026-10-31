package dev.sam3vision.core.danger

/**
 * Lecture d'approche d'une piste : vient-elle vers le téléphone ?
 *
 * [approaching] = la boîte grossit vite ([growthPerSec] ≥ seuil) ET elle est
 * centrée ([centered]) — quelqu'un qui traverse le bord du champ ne compte pas
 * — OU la trajectoire latérale croise le couloir ([crossing]).
 * [ttcSec] = temps-avant-contact optique (`taille / taux`, secondes) pour
 * l'approche de face, délai d'entrée dans le couloir pour la croisée
 * (0 = déjà dedans, traverse devant) ; présent seulement quand ça s'approche.
 * À vitesse constante, la taille apparente suit `s(t) ∝ 1 / (ttc − t)`, donc
 * `ttc ≈ s / (ds/dt)`.
 */
data class ApproachReading(
    val trackId: Int,
    val approaching: Boolean,
    val centered: Boolean,
    val growthPerSec: Float,
    val ttcSec: Float?,
    /** Vitesse latérale (centre x normalisé / s, < 0 = vers la gauche). */
    val lateralSpeedPerSec: Float = 0f,
    /** Vrai quand la trajectoire latérale croise le couloir central. */
    val crossing: Boolean = false,
)

/**
 * Lit l'approche sur l'historique des tailles d'une piste : on garde les
 * (instant, taille, centre x) de la fenêtre ([windowMs]), les taux viennent
 * de la plus vieille à la plus récente. Pur Kotlin, sans Android.
 *
 * La taille est `√aire` normalisée : elle grandit linéairement quand la
 * personne avance à vitesse constante (l'aire, elle, grandit au carré).
 * Le latéral lit le centre x : cap + vitesse → la trajectoire coupe-t-elle
 * le couloir ([centerMargin]) sous [crossingHorizonSec] ? Un traverseur
 * rapide alerte même sans grossir.
 */
class ApproachEstimator(
    /** Fenêtre d'historique : 1,5 s de marche, assez pour un taux stable. */
    val windowMs: Long = 1_500L,
    /** Taux minimal : +3 % de taille normalisée par seconde. */
    val minGrowthPerSec: Float = 0.03f,
    /** Demi-largeur du couloir central : |cx − 0,5| ≤ 0,25. */
    val centerMargin: Float = 0.25f,
    /** Écart minimal oldest→newest : sous 300 ms, pas de taux fiable. */
    val minDtMs: Long = 300L,
    /** Vitesse latérale minimale : 0,15 largeur/s (priors, à valider dehors). */
    val minLateralSpeedPerSec: Float = 0.15f,
    /** Horizon de croisée : le couloir est coupé sous 2 s (priors). */
    val crossingHorizonSec: Float = 2f,
) {
    private data class Sample(val atMs: Long, val size: Float, val cx: Float)

    private val history = mutableMapOf<Int, ArrayDeque<Sample>>()

    /**
     * Ajoute la piste vue à [nowMs], rend sa lecture d'approche. Une piste vue
     * pour la première fois n'a pas de taux : `approaching = false`.
     */
    fun update(track: DangerTrack, nowMs: Long): ApproachReading {
        val samples = history.getOrPut(track.id) { ArrayDeque() }
        samples.addLast(Sample(nowMs, sizeOf(track), track.centerX))
        prune(samples, nowMs)
        val rate = growthRate(samples)
        val lateral = lateralRate(samples)
        val centered = kotlin.math.abs(track.centerX - 0.5f) <= centerMargin
        val face = rate != null && rate >= minGrowthPerSec && centered
        val crossTtc = crossingTtc(track.centerX, lateral)
        val approaching = face || crossTtc != null
        val ttc = if (face) sizeOf(track) / rate!! else crossTtc
        return ApproachReading(track.id, approaching, centered, rate ?: 0f, ttc, lateral ?: 0f, crossTtc != null)
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

    /** Vitesse latérale oldest→newest, `null` si la fenêtre est trop courte. */
    private fun lateralRate(samples: ArrayDeque<Sample>): Float? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val dtSec = (last.atMs - first.atMs) / 1000f
        if (samples.size < 2 || dtSec * 1000f < minDtMs) return null
        return (last.cx - first.cx) / dtSec
    }

    /**
     * Délai de croisée du couloir (secondes), `null` si la trajectoire ne le
     * coupe pas : trop lente, cap dehors, ou entrée au-delà de l'horizon.
     * Déjà dedans + vite = 0 (traverse devant, immédiat).
     */
    private fun crossingTtc(cx: Float, lateral: Float?): Float? {
        if (lateral == null || kotlin.math.abs(lateral) < minLateralSpeedPerSec) return null
        val offset = cx - 0.5f
        if (kotlin.math.abs(offset) <= centerMargin) return 0f
        // Signe vitesse vs côté : même signe = s'éloigne du centre.
        if (kotlin.math.sign(lateral) == kotlin.math.sign(offset)) return null
        val edge = 0.5f + kotlin.math.sign(offset) * centerMargin
        val entrySec = kotlin.math.abs(cx - edge) / kotlin.math.abs(lateral)
        return if (entrySec <= crossingHorizonSec) entrySec else null
    }

    /** Ne garde que la fenêtre ([windowMs]) précédant [nowMs]. */
    private fun prune(samples: ArrayDeque<Sample>, nowMs: Long) {
        while (samples.size > 1 && nowMs - samples.first().atMs > windowMs) {
            samples.removeFirst()
        }
    }
}
