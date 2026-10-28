package dev.sam3vision.core.nav

import dev.sam3vision.core.net.ApiError

/**
 * Adresse résolue en coordonnées GPS : l'entrée de Valhalla `/route`, qui ne
 * prend que du lat/lon (son action `search` n'existe pas côté déployé).
 *
 * @param score pertinence 0..1 quand le fournisseur la donne (BAN), `null`
 * sinon (le géocodeur Android ordonne déjà par pertinence, meilleur premier).
 */
data class GeocodedPlace(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val score: Float? = null,
)

/** Message unique de la requête vide, partagé avec [DestinationError.BlankQuery]. */
private val BLANK_MESSAGE: String = DestinationError.BlankQuery().message.orEmpty()

/**
 * Erreurs de géocodage typées, message directement affichable ou annonçable.
 * Jamais levées : renvoyées par [Result.failure].
 */
sealed class GeocodeError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Rien à géocoder : vide, blanc, ou ponctuation seule. */
    class BlankQuery : GeocodeError(BLANK_MESSAGE)

    /** Les deux fournisseurs ont répondu, sans aucun candidat. */
    class NotFound(query: String) : GeocodeError(
        "adresse introuvable : précise le numéro et la ville (« ${query.take(80)} »)",
    )

    /** Les deux fournisseurs ont dépassé leur délai. */
    class TimedOut : GeocodeError("géocodage trop lent : réessaie dans un instant")

    /** Panne réseau (fournisseur injoignable). */
    class Network(
        message: String = "géocodage impossible : réseau injoignable",
        cause: Throwable? = null,
    ) : GeocodeError(message, cause)

    /** Fournisseur absent ou en erreur (pas de backend géocodeur, HTTP 5xx…). */
    class ServiceUnavailable(
        message: String = "service d'adresses indisponible",
        cause: Throwable? = null,
    ) : GeocodeError(message, cause)
}

/**
 * Fournisseur d'adresses -> coordonnées, meilleur candidat premier.
 * [query] est déjà normalisée ([DestinationQuery.normalize]).
 */
interface GeocodeSource {
    suspend fun geocode(query: String, limit: Int = DEFAULT_LIMIT): Result<List<GeocodedPlace>>

    companion object {
        /** Candidats demandés : 3 suffit, le premier est retenu. */
        const val DEFAULT_LIMIT = 3
    }
}

/** Projette toute erreur fournisseur vers [GeocodeError] (passe-déjà si typée). */
internal fun Throwable.toGeocodeError(): GeocodeError = when (this) {
    is GeocodeError -> this
    is ApiError.Timeout -> GeocodeError.TimedOut()
    is ApiError.Network -> GeocodeError.Network(cause = this)
    is ApiError -> GeocodeError.ServiceUnavailable(message.orEmpty(), this)
    else -> GeocodeError.Network(message ?: "géocodage impossible", this)
}

/** Re-typage d'un échec sans toucher au succès. */
internal fun <T> Result<T>.mapError(block: (Throwable) -> Throwable): Result<T> =
    fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(block(it)) },
    )
