package dev.sam3vision.core.navcore

/**
 * Ponts [Result] ↔ [NavError] du parcours navigation vocale. Purs, sans
 * coroutine : les appelants restent sur le style [Result] existant (`MapsUri`,
 * `DestinationQuery`, lanceurs) et normalisent leurs échecs ici.
 */
object NavResults {

    /**
     * Double échec ASR (Meta puis Scribe) fusionné en [NavError.AsrFailed]
     * portant « Erreur. \<msg Meta\>. \<msg Scribe\>. » (spéc. § c.5).
     * Ne lève jamais.
     */
    fun asrFailure(primary: Throwable, fallback: Throwable): NavError.AsrFailed =
        NavError.AsrFailed(NavMessages.asrBothFailed(navLabelOf(primary), navLabelOf(fallback)))

    /**
     * Réécrit l'échec éventuel de ce [Result] en [NavError] via [transform]
     * ([NavError.of] par défaut). Le succès traverse inchangé. Ne lève jamais.
     */
    fun <T> Result<T>.mapNavError(transform: (Throwable) -> NavError = NavError.Companion::of): Result<T> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(runCatching { transform(it) }.getOrElse { e -> NavError.of(e) }) },
        )

    /**
     * L'échec typé de ce [Result], ou `null` en cas de succès.
     * Raccourci de lecture pour brancher [NavUiState.Failed] et [RetryPolicy].
     */
    fun <T> Result<T>.navError(transform: (Throwable) -> NavError = NavError.Companion::of): NavError? =
        fold(onSuccess = { null }, onFailure = { runCatching { transform(it) }.getOrElse { e -> NavError.of(e) } })
}
