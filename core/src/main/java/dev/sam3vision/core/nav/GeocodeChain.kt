package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Chaîne de géocodage : normalisation ([DestinationQuery]) -> cache mémoire ->
 * fournisseur principal (géocodeur Android, GMS) -> repli BAN
 * (api-adresse.data.gouv.fr). Chaque fournisseur a son propre délai ; un vide
 * ou un échec du principal bascule sur le repli, jamais d'exception.
 * Avec une origine (position GPS du téléphone), le candidat retenu est le
 * plus proche de l'origine au lieu du premier : les homonymes lointains
 * (« Sacré-Cœur » au Pas-de-Calais pour un Parisien) ne gagnent plus.
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
     * [near] (origine GPS) trie par proximité et élargit la demande à
     * [NEARBY_LIMIT] candidats ; `null` garde l'ordre fournisseur.
     */
    suspend fun geocode(
        rawAddress: String,
        limit: Int = GeocodeSource.DEFAULT_LIMIT,
        near: RoutePoint? = null,
    ): Result<GeocodedPlace> {
        val query = DestinationQuery.normalize(rawAddress).getOrElse {
            return Result.failure(GeocodeError.BlankQuery())
        }
        val key = cacheKey(query, near)
        cache[key]?.let { return Result.success(it) }
        val effectiveLimit = if (near == null) limit else maxOf(limit, NEARBY_LIMIT)
        val first = guarded(primary, query, effectiveLimit, primaryTimeoutMs)
        first.getOrNull()?.pickNear(near)?.let { return hit(key, it) }
        val second = guarded(fallback, query, effectiveLimit, fallbackTimeoutMs)
        second.getOrNull()?.pickNear(near)?.let { return hit(key, it) }
        return Result.failure(combineErrors(query, first, second))
    }

    private fun hit(key: String, place: GeocodedPlace): Result<GeocodedPlace> {
        GeocodeCache.put(cache, key, place)
        return Result.success(place)
    }

    /**
     * Retient un candidat : le plus proche de [near] (haversine) quand
     * l'origine est connue, sinon le premier (ordre du fournisseur).
     */
    private fun List<GeocodedPlace>.pickNear(near: RoutePoint?): GeocodedPlace? {
        if (near == null) return firstOrNull()
        return minByOrNull { NavGeo.haversineMeters(near.lat, near.lon, it.latitude, it.longitude) }
    }

    /**
     * Clé de cache : la requête seule sans origine (historique), sinon la
     * requête + la cellule ~1 km de l'origine (même nom, deux villes).
     */
    private fun cacheKey(query: String, near: RoutePoint?): String {
        if (near == null) return query
        val lat = (near.lat * 100).roundToInt()
        val lon = (near.lon * 100).roundToInt()
        return "$query@$lat,$lon"
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

        /** Candidats demandés avec une origine : le bon est souvent 3e-5e (homonymes BAN). */
        const val NEARBY_LIMIT = 5
    }
}
