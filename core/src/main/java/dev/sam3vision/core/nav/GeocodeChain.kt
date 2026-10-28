package dev.sam3vision.core.nav

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Chaîne de géocodage : normalisation ([DestinationQuery]) -> cache mémoire ->
 * fournisseur principal (géocodeur Android, GMS) -> repli BAN
 * (api-adresse.data.gouv.fr). Chaque fournisseur a son propre délai ; un vide
 * ou un échec du principal bascule sur le repli, jamais d'exception.
 */
class GeocodeChain(
    private val primary: GeocodeSource,
    private val fallback: GeocodeSource,
    private val cache: LinkedHashMap<String, GeocodedPlace> = GeocodeCache.shared,
    private val primaryTimeoutMs: Long = PRIMARY_TIMEOUT_MS,
    private val fallbackTimeoutMs: Long = FALLBACK_TIMEOUT_MS,
) {
    /**
     * Résout [rawAddress] en meilleur candidat. Échecs typés [GeocodeError] :
     * [GeocodeError.BlankQuery] sans toucher aux fournisseurs, [GeocodeError.NotFound]
     * si les deux rendent vide, sinon l'erreur du repli (la plus actionnable).
     */
    suspend fun geocode(rawAddress: String, limit: Int = GeocodeSource.DEFAULT_LIMIT): Result<GeocodedPlace> {
        val query = DestinationQuery.normalize(rawAddress).getOrElse {
            return Result.failure(GeocodeError.BlankQuery())
        }
        cache[query]?.let { return Result.success(it) }
        val first = guarded(primary, query, limit, primaryTimeoutMs)
        first.getOrNull()?.firstOrNull()?.let { return hit(query, it) }
        val second = guarded(fallback, query, limit, fallbackTimeoutMs)
        second.getOrNull()?.firstOrNull()?.let { return hit(query, it) }
        return Result.failure(combineErrors(query, first, second))
    }

    private fun hit(query: String, place: GeocodedPlace): Result<GeocodedPlace> {
        GeocodeCache.put(cache, query, place)
        return Result.success(place)
    }

    /** Un fournisseur sous délai, erreur re-typée ; l'annulation externe remonte. */
    private suspend fun guarded(
        source: GeocodeSource,
        query: String,
        limit: Int,
        timeoutMs: Long,
    ): Result<List<GeocodedPlace>> = try {
        withTimeout(timeoutMs) { source.geocode(query, limit) }.mapError { it.toGeocodeError() }
    } catch (t: TimeoutCancellationException) {
        coroutineContext.ensureActive()
        Result.failure(GeocodeError.TimedOut())
    }

    /** L'erreur du repli prime (réseau actionnable), puis celle du principal. */
    private fun combineErrors(
        query: String,
        first: Result<List<GeocodedPlace>>,
        second: Result<List<GeocodedPlace>>,
    ): GeocodeError {
        second.exceptionOrNull()?.let { return it.toGeocodeError() }
        first.exceptionOrNull()?.let { return it.toGeocodeError() }
        return GeocodeError.NotFound(query)
    }

    companion object {
        /** Géocodeur local GMS : rapide ou absent, 8 s suffisent. */
        const val PRIMARY_TIMEOUT_MS = 8_000L

        /** BAN par-dessus le réseau : 10 s, aligné sur son client HTTP. */
        const val FALLBACK_TIMEOUT_MS = 10_000L
    }
}
