package dev.sam3vision.core.net

import okhttp3.OkHttpClient

/**
 * Délai de l'extinction : long, parce que la requête peut arriver sur un conteneur
 * endormi — elle attend alors son démarrage (2 à 7 s sur restore, sinon boot complet
 * 18-32 s : le restore est opportuniste, ~1 min après la mort). Un délai court
 * échouerait au pire moment, celui où l'app se ferme.
 */
const val RELEASE_TIMEOUT_SECONDS: Long = 45L

/** En-tête exigé par le backend : sans lui, `/release` répond 400 (pas d'extinction par erreur). */
internal const val RELEASE_HEADER = "x-sam-release"

/**
 * Refus qui ne sont **pas** un simple verdict : clé refusée (401/403), crédits épuisés
 * (402), quota atteint (429). Les avaler en `released=false` laissait la carte allumée
 * sans que l'écran de fermeture puisse dire pourquoi.
 */
private val TYPED_REFUSALS = setOf(401, 402, 403, 429)

/**
 * Éteint le conteneur GPU (`POST /release`), appelé quand l'app se ferme.
 *
 * Sans ça, la carte A100 reste allumée jusqu'à la fenêtre de réduintage — et tant
 * qu'un client sonde `/health`, elle ne s'éteint jamais. Le backend tue son propre
 * processus après avoir répondu ; `min_containers=0` fait que Modal ne le relance pas,
 * et le prochain réveil est un restore (2 à 7 s) ou un boot complet (18-32 s).
 *
 * **Une seule requête par appel** : le rejeu d'un refus doublait l'extinction (un 401 ou un
 * 429 partait deux fois). `Result.success(false)` = le serveur a refusé (400, 404, 5xx) ;
 * `failure` = réseau, délai dépassé, ou refus typé ([ApiError.InvalidKey],
 * [ApiError.PaymentRequired], [ApiError.RateLimited]).
 */
suspend fun releaseSam(
    baseUrl: String,
    client: OkHttpClient = defaultHttpClient(RELEASE_TIMEOUT_SECONDS),
): Result<ReleaseOutcome> {
    if (baseUrl.isBlank()) return Result.success(ReleaseOutcome(false, 0, "base vide"))
    // Construite dans `executeBuilt` : une URL malformée devient une erreur, pas un crash.
    return client.executeBuilt {
        requestTo(
            baseUrl,
            "/release",
            "POST",
            jsonBody(EMPTY_JSON),
            headers = mapOf(RELEASE_HEADER to "confirm"),
        )
    }.then { outcome -> outcome.toRelease() }
}

/** Réponse de `/release` : le verdict, le code HTTP et le corps (pour le diagnostic). */
data class ReleaseOutcome(val released: Boolean, val code: Int, val body: String)

/**
 * Verdict d'un appel : `released` seulement sur un 2xx qui le dit, avec le code et le corps
 * conservés pour le diagnostic. Un [TYPED_REFUSALS] remonte l'erreur de [httpError] — même
 * type qu'un refus identique sur les autres routes — au lieu d'un `false` muet.
 */
private fun HttpOutcome.toRelease(): Result<ReleaseOutcome> {
    if (code in TYPED_REFUSALS) return Result.failure(httpError(code, body))
    return Result.success(
        ReleaseOutcome(
            released = code in 200..299 && parse(body)?.optBoolean("released", false) == true,
            code = code,
            body = body.take(200),
        ),
    )
}

private const val EMPTY_JSON = "{}"
