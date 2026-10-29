package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.net.HttpRouteClient
import dev.sam3vision.core.net.ROUTE_TIMEOUT_SECONDS
import dev.sam3vision.core.net.RouteClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Chargeur de résumé Valhalla pour l'écran de confirmation : destination →
 * géocodage (chaîne GMS puis BAN) → itinéraire piéton depuis [originNow].
 *
 * - géocodage en échec → échec (son message français, affiché tel quel) ;
 * - sans position de départ → échec « position de départ indisponible
 *   (activez la localisation) » ; sans URL Valhalla → échec « service
 *   d'itinéraire non configuré » (jamais de résumé à zéro : des zéros
 *   affichés comme des distances mentent — « Dans 0 m ») ;
 * - appel `/route` : 2 tentatives max (1 essai + 1 reprise), chacune bornée
 *   à [ROUTE_TIMEOUT_SECONDS] ; échec final → échec « itinéraire
 *   indisponible (motif) » (l'écran montre l'adresse + le motif, et
 *   « Démarrer » bascule sur Google Maps).
 *
 * Ne lève jamais : tout collaborateur qui jetterait devient un échec.
 */
class ValhallaRouteLoader(
    private val geocode: suspend (String) -> Result<GeocodedPlace>,
    private val originNow: () -> RoutePoint?,
    private val routeClient: (String) -> RouteClient = ::HttpRouteClient,
    private val baseUrl: () -> String,
    private val routeTimeoutMs: Long = ROUTE_TIMEOUT_SECONDS * 1000,
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
            return Result.failure(missingInputFailure(origin == null))
        }
        val client = try {
            routeClient(base)
        } catch (t: Exception) {
            return Result.failure(routeFailure(t))
        }
        val trip = loadRoute(client, origin, RoutePoint(place.latitude, place.longitude))
            .getOrElse { return Result.failure(routeFailure(it)) }
        return Result.success(
            RouteSummary(
                destination,
                place.label,
                trip.lengthMeters.toLong().coerceAtLeast(0),
                trip.timeSeconds.toLong().coerceAtLeast(0),
            ),
        )
    }

    /** Appel `/route` : 1 reprise sur échec (réseau, délai, jeté). */
    private suspend fun loadRoute(client: RouteClient, from: RoutePoint, to: RoutePoint): Result<RouteTrip> {
        val first = timedRoute(client, from, to)
        if (first.isSuccess) return first
        return timedRoute(client, from, to)
    }

    /** Une tentative `/route`, bornée : tout échec devient un `Result`. */
    private suspend fun timedRoute(client: RouteClient, from: RoutePoint, to: RoutePoint): Result<RouteTrip> =
        try {
            withTimeout(routeTimeoutMs) { client.route(from, to) }
        } catch (t: TimeoutCancellationException) {
            Result.failure(t)
        } catch (t: Exception) {
            Result.failure(t)
        }
}

/**
 * Entrée manquante : motif français actionnable (jamais un résumé à zéro,
 * que l'écran afficherait comme des distances — « Dans 0 m »).
 */
private fun missingInputFailure(noOrigin: Boolean): Throwable =
    IllegalStateException(
        if (noOrigin) {
            "position de départ indisponible (activez la localisation)"
        } else {
            "service d'itinéraire non configuré"
        },
    )

/** Échec `/route` final : motif français actionnable pour l'écran. */
private fun routeFailure(cause: Throwable): Throwable {
    val reason = when (cause) {
        is TimeoutCancellationException -> "délai dépassé"
        else -> cause.message?.trim().orEmpty().ifEmpty { "réseau inaccessible" }
    }
    return IllegalStateException("itinéraire indisponible ($reason)")
}
