package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.net.HttpRouteClient
import dev.sam3vision.core.net.RouteClient

/**
 * Chargeur de résumé Valhalla pour l'écran de confirmation : destination →
 * géocodage (chaîne GMS puis BAN) → itinéraire piéton depuis [originNow].
 *
 * - géocodage en échec → échec (son message français, affiché tel quel) ;
 * - sans position de départ ou sans URL Valhalla → résumé adresse seule
 *   (distance et durée à zéro, l'écran montre la destination) ;
 * - itinéraire en échec → échec (l'écran montre l'adresse + le motif, et
 *   « Démarrer » bascule sur Google Maps).
 *
 * Ne lève jamais : tout collaborateur qui jetterait devient un échec.
 */
class ValhallaRouteLoader(
    private val geocode: suspend (String) -> Result<GeocodedPlace>,
    private val originNow: () -> RoutePoint?,
    private val routeClient: (String) -> RouteClient = ::HttpRouteClient,
    private val baseUrl: () -> String,
) {

    suspend operator fun invoke(destination: String): Result<RouteSummary> {
        // Appels `suspend` (`runCatching` ne l'est pas) : try/catch directs.
        val place = try {
            geocode(destination)
        } catch (t: Exception) {
            Result.failure(t)
        }.getOrElse { return Result.failure(it) }
        val origin = runCatching { originNow() }.getOrNull()
        val base = runCatching { baseUrl().trim().trimEnd('/') }.getOrDefault("")
        if (origin == null || base.isNullOrBlank()) {
            return Result.success(RouteSummary(destination, place.label, 0, 0))
        }
        val trip = try {
            routeClient(base).route(origin, RoutePoint(place.latitude, place.longitude))
        } catch (t: Exception) {
            Result.failure(t)
        }.getOrElse { return Result.failure(it) }
        return Result.success(
            RouteSummary(
                destination,
                place.label,
                trip.lengthMeters.toLong().coerceAtLeast(0),
                trip.timeSeconds.toLong().coerceAtLeast(0),
            ),
        )
    }
}
