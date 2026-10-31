package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.toNavRoute
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.net.HttpRouteClient
import dev.sam3vision.core.net.ROUTE_TIMEOUT_SECONDS
import dev.sam3vision.core.net.RouteClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Chargeur de résumé Valhalla pour l'écran de confirmation : position lue
 * d'abord ([originNow]) → géocodage trié par proximité → itinéraire piéton.
 *
 * - sans position de départ → échec « position de départ indisponible
 *   (activez la localisation) » (sans appel réseau) ; sans URL Valhalla →
 *   échec « service d'itinéraire non configuré » (jamais de résumé à zéro :
 *   des zéros affichés comme des distances mentent — « Dans 0 m ») ;
 * - géocodage en échec → échec (son message français, affiché tel quel) ;
 * - appel `/route` : 2 tentatives max (1 essai + 1 reprise), chacune bornée
 *   à [ROUTE_TIMEOUT_SECONDS] ; échec final → échec « itinéraire
 *   indisponible (motif) » (l'écran montre l'adresse + le motif, et
 *   « Démarrer » annonce l'indisponibilité).
 *
 * - aperçu carte ([onPreview]) : `null` au lancement, puis l'itinéraire
 *   guidable ([RouteTrip.toNavRoute], `null` si inconvertible) — zéro appel
 *   réseau de plus, le résumé et l'aperçu partagent le même `/route` ;
 *
 * Ne lève jamais : tout collaborateur qui jetterait devient un échec.
 */
class ValhallaRouteLoader(
    private val geocode: suspend (String, RoutePoint?) -> Result<GeocodedPlace>,
    private val originNow: suspend () -> RoutePoint?,
    private val routeClient: (String) -> RouteClient = ::HttpRouteClient,
    private val baseUrl: () -> String,
    private val routeTimeoutMs: Long = ROUTE_TIMEOUT_SECONDS * 1000,
    /** Reçoit l'itinéraire guidable (`null` en chargement ou en échec) pour la carte d'aperçu. */
    private val onPreview: (NavRoute?) -> Unit = {},
) {

    suspend operator fun invoke(destination: String): Result<RouteSummary> {
        // Nouvelle demande : l'aperçu précédent est périmé (jamais d'ancienne carte).
        runCatching { onPreview(null) }
        // Position d'abord (attend un fix frais si besoin) : sans GPS, échec sans appel réseau.
        val origin = try {
            originNow()
        } catch (_: Exception) {
            null
        }
        val base = runCatching { baseUrl().trim().trimEnd('/') }.getOrDefault("")
        if (origin == null || base.isNullOrBlank()) {
            return Result.failure(missingInputFailure(origin == null))
        }
        // Appels `suspend` (`runCatching` ne l'est pas) : try/catch directs.
        // L'origine trie les candidats par proximité (homonymes lointains écartés).
        val place = try {
            geocode(destination, origin)
        } catch (t: Exception) {
            Result.failure(t)
        }.getOrElse { return Result.failure(it) }
        val client = try {
            routeClient(base)
        } catch (t: Exception) {
            return Result.failure(routeFailure(t))
        }
        val trip = loadRoute(client, origin, RoutePoint(place.latitude, place.longitude))
            .onSuccess { runCatching { onPreview(it.toNavRoute(destination).getOrNull()) } }
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
 * Entrée manquante : motif anglais actionnable (jamais un résumé à zéro,
 * que l'écran afficherait comme des distances — « In 0 m »).
 */
private fun missingInputFailure(noOrigin: Boolean): Throwable =
    IllegalStateException(
        if (noOrigin) {
            "starting position unavailable (enable location)"
        } else {
            "route service not configured"
        },
    )

/** Fragment stable du refus Valhalla quand le vol d'oiseau dépasse son plafond (peu importe sa valeur). */
private const val VALHALLA_DISTANCE_LIMIT = "exceeds the max distance limit"

/** Échec `/route` final : motif anglais actionnable pour l'écran (jamais du JSON brut). */
private fun routeFailure(cause: Throwable): Throwable {
    val reason = when (cause) {
        is TimeoutCancellationException -> "timeout"
        else -> {
            val raw = cause.message?.trim().orEmpty().ifEmpty { "network unreachable" }
            if (VALHALLA_DISTANCE_LIMIT in raw) "destination too far on foot" else raw
        }
    }
    return IllegalStateException("route unavailable ($reason)")
}
