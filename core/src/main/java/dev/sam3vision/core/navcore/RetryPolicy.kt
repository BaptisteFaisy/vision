package dev.sam3vision.core.navcore

/**
 * Politique de réessai à backoff exponentiel, pure et déterministe (sans
 * gigue : reproductible et verrouillée par `RetryPolicyTest` — l'appelant
 * ajoute sa gigue s'il en veut).
 *
 * Aucune attente ici : [delayFor] ne fait que calculer le délai en ms, c'est
 * l'appelant (coroutine) qui dort. Zéro dépendance Android.
 *
 * @param maxAttempts tentatives au total (1 = aucun réessai), ≥ 1.
 * @param baseDelayMs délai avant la 2ᵉ tentative, ≥ 0.
 * @param maxDelayMs plafond d'un délai, ≥ [baseDelayMs].
 * @param factor multiplicateur par tentative, ≥ 1.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 8_000L,
    val factor: Double = 2.0,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelayMs >= 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must cover baseDelayMs" }
        require(factor >= 1.0) { "factor must be at least 1" }
    }

    /**
     * Délai à attendre avant la tentative [attempt] (1 = première : 0 ms,
     * 2 = [baseDelayMs], puis × [factor], plafonné à [maxDelayMs]).
     */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return 0L
        var delay = baseDelayMs.toDouble()
        repeat(attempt - 2) { delay *= factor }
        return delay.coerceAtMost(maxDelayMs.toDouble()).toLong()
    }

    /**
     * `true` s'il reste des tentatives ([attempt] est le numéro de celle qui
     * vient d'échouer) et que [error] est [récupérable][NavError.retryable].
     */
    fun shouldRetry(error: NavError, attempt: Int): Boolean =
        attempt < maxAttempts && error.retryable
}
