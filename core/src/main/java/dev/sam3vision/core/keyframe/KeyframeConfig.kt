package dev.sam3vision.core.keyframe

/**
 * Tous les réglages du [KeyframeScheduler], transcrits de la lane L13 (u12, vague 2). Chaque
 * valeur vient d'un arbitrage déjà tranché : ne pas « recalculer » un réglage ici.
 *
 * | réglage | défaut | pourquoi |
 * |---|---|---|
 * | [minIntervalMs] | 200 ms | borne basse du rythme, et du coût GPU |
 * | [maxAgeMs] | 500 ms | fraîcheur garantie des masques |
 * | [staticSlackMaxAgeMs] | 1 000 ms | **off par défaut** : hors bande 200–500 ms du plan |
 * | [errorThreshold] / [emaAlpha] | 0,12 / 0,40 | désaccord de masque lissé, sur 2 frames |
 * | [sceneThreshold] | 0,35 | coupure de scène, prioritaire |
 * | [budgetRatePerSec] / [budgetBurst] | 3 / 2 | lien montant téléphone + GPU (0,237 $/1000 frames) |
 * | [inFlightTimeoutMs] | 12 s | A/R mesuré le pire : 1 220–1 260 ms, marge ×10 |
 * | [backoffMs] | 0,5 → 8 s | pas de boucle sur un backend mort |
 *
 * Un réglage hors domaine lève une exception **au montage** (pas en vol) : un scheduler
 * mal configuré ne doit pas se découvrir au milieu du direct.
 */
data class KeyframeConfig(
    val minIntervalMs: Long = 200L,
    val maxAgeMs: Long = 500L,
    val staticSlackMaxAgeMs: Long = 1_000L,
    val allowStaticSlack: Boolean = false,
    val errorThreshold: Float = 0.12f,
    val errorFrames: Int = 2,
    val emaAlpha: Float = 0.40f,
    val sceneThreshold: Float = 0.35f,
    val budgetRatePerSec: Float = 3f,
    val budgetBurst: Int = 2,
    val inFlightTimeoutMs: Long = 12_000L,
    val backoffMs: List<Long> = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L),
) {

    init {
        require(minIntervalMs > 0L) { "minIntervalMs must be > 0" }
        require(maxAgeMs >= minIntervalMs) { "maxAgeMs must be >= minIntervalMs" }
        require(staticSlackMaxAgeMs >= maxAgeMs) { "staticSlackMaxAgeMs must be >= maxAgeMs" }
        require(errorThreshold in 0f..1f) { "errorThreshold must be within [0, 1]" }
        require(errorFrames >= 1) { "errorFrames must be >= 1" }
        require(emaAlpha > 0f && emaAlpha <= 1f) { "emaAlpha must be within ]0, 1]" }
        require(sceneThreshold > 0f && sceneThreshold <= 1f) { "sceneThreshold must be within ]0, 1]" }
        require(budgetRatePerSec > 0f) { "budgetRatePerSec must be > 0" }
        require(budgetBurst >= 1) { "budgetBurst must be >= 1" }
        require(inFlightTimeoutMs > minIntervalMs) { "inFlightTimeoutMs must be > minIntervalMs" }
        require(backoffMs.isNotEmpty() && backoffMs.all { it > 0L }) {
            "the fallback must be a non-empty list of durations > 0"
        }
    }

    /**
     * Plafond d'âge **effectif** : 1 000 ms seulement si la veille statique est autorisée.
     * La veille viole la bande 200–500 ms du plan : elle n'est activable qu'après validation
     * terrain, et le scheduler la refuse par défaut.
     */
    val effectiveMaxAgeMs: Long
        get() = if (allowStaticSlack) staticSlackMaxAgeMs else maxAgeMs

    /**
     * Borne haute du rythme, **par seconde** : rafale + débit + l'unique jeton d'urgence.
     * C'est la garantie de coût à opposer au GPU (3 req/s ⇒ ≤ 2,6 $/h sur A100).
     */
    val maxRequestsPerSecond: Int
        get() = budgetBurst + budgetRatePerSec.toInt() + 1

    /** Durée du repli après [failures] échecs consécutifs (la dernière durée est répétée). */
    fun backoffAfterFailures(failures: Int): Long =
        backoffMs[(failures - 1).coerceIn(0, backoffMs.size - 1)]
}