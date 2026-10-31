package dev.sam3vision.core.net

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RouteManeuver
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import dev.sam3vision.core.nav.decodeValhallaShape
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Un itinéraire piéton répond vite : garde-fou à 15 s, comme le tracker. */
const val ROUTE_TIMEOUT_SECONDS: Long = 15L

/** User-Agent propre à l'itinéraire, identifiable côté backend. */
internal const val ROUTE_USER_AGENT = "$USER_AGENT route"

/** Coût Valhalla de la marche : le seul mode du guidage vocal. */
const val ROUTE_COSTING_PEDESTRIAN = "pedestrian"

/** Langue des consignes demandée au serveur : le téléphone parle anglais. */
internal const val ROUTE_LANGUAGE = "en-US"

/** Valhalla répond en kilomètres (`units: kilometers`) : passage en mètres. */
private const val KM_TO_METERS = 1000.0

/**
 * Client de l'itinéraire Valhalla (`POST /route`) : d'un point GPS à une
 * destination géocodée, en mode piéton. Le serveur ne géocode pas (`/search`
 * n'existe pas sur Valhalla) : l'appelant fournit des coordonnées, jamais
 * une adresse en texte.
 */
interface RouteClient {
    suspend fun route(
        from: RoutePoint,
        to: RoutePoint,
        costing: String = ROUTE_COSTING_PEDESTRIAN,
    ): Result<RouteTrip>

    /** `GET /status` : vrai si Valhalla répond 2xx (le tunnel est vivant). */
    suspend fun health(): Result<Boolean>
}

/**
 * Implémentation HTTP réelle, contrat Valhalla `POST /route` :
 * `locations` + `costing` + `directions_options.language`. Réutilise le client
 * HTTP unique de `:core`, [requestTo] (URL + User-Agent), [OneShotJsonBody] et
 * [Json.kt] comme tout autre client : aucune requête construite à la main ici.
 */
class HttpRouteClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(ROUTE_TIMEOUT_SECONDS),
) : RouteClient {

    override suspend fun route(from: RoutePoint, to: RoutePoint, costing: String): Result<RouteTrip> {
        endpointRefusal("route endpoint", baseUrl, "/route")?.let { return Result.failure(it) }
        return client.executeBuilt {
            requestTo(baseUrl, "/route", "POST", OneShotJsonBody(routeRequestJson(from, to, costing)), ROUTE_USER_AGENT)
        }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseRouteTrip(raw) } }
    }

    override suspend fun health(): Result<Boolean> {
        endpointRefusal("route endpoint", baseUrl, "/status")?.let { return Result.failure(it) }
        return client.execute(requestTo(baseUrl, "/status"))
            .then { Result.success(it.code in 200..299) }
    }
}

/** Corps `/route` manuel : deux points, un coût, la langue des consignes. */
internal fun routeRequestJson(from: RoutePoint, to: RoutePoint, costing: String): String =
    "{\"locations\":[{\"lat\":" + from.lat + ",\"lon\":" + from.lon + "}," +
        "{\"lat\":" + to.lat + ",\"lon\":" + to.lon + "}]," +
        "\"costing\":\"" + jsonEscape(costing) + "\"," +
        "\"directions_options\":{\"language\":\"" + ROUTE_LANGUAGE + "\"}}"

/** Réponse `/route` → itinéraire : tronçons décodés, totaux du `summary`. */
internal fun parseRouteTrip(raw: String): RouteTrip {
    val json = parse(raw) ?: throw ApiError.BadResponse("/route: unreadable JSON")
    val trip = json.optJSONObject("trip") ?: throw ApiError.BadResponse("/route: missing trip")
    val legs = trip.objects("legs").map { leg -> parseRouteLeg(leg) }
    if (legs.isEmpty()) throw ApiError.BadResponse("/route: no legs")
    val summary = trip.optJSONObject("summary")
    return RouteTrip(
        legs = legs,
        lengthMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * KM_TO_METERS,
        timeSeconds = summary?.optDouble("time", 0.0) ?: 0.0,
    )
}

/** Un tronçon : `shape` décodé une fois ici, manœuvres lues dans l'ordre. */
private fun parseRouteLeg(json: JSONObject): RouteLeg {
    val summary = json.optJSONObject("summary")
    return RouteLeg(
        shape = decodeValhallaShape(json.str("shape")),
        maneuvers = json.objects("maneuvers").map { maneuver -> parseRouteManeuver(maneuver) },
        lengthMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * KM_TO_METERS,
        timeSeconds = summary?.optDouble("time", 0.0) ?: 0.0,
    )
}

/**
 * Une manœuvre : la consigne parlée **avant** la transition prime (c'est elle
 * qui prévient), la consigne d'après ne sert qu'en repli ; sans les deux, le
 * guidage retombe sur `maneuverShort(type)`.
 */
private fun parseRouteManeuver(json: JSONObject): RouteManeuver = RouteManeuver(
    type = json.intOr("type"),
    instruction = json.str("instruction"),
    verbalInstruction = json.str("verbal_pre_transition_instruction")
        .ifBlank { json.str("verbal_post_transition_instruction") },
    streetNames = json.strings("street_names"),
    lengthMeters = json.optDouble("length", 0.0) * KM_TO_METERS,
    timeSeconds = json.optDouble("time", 0.0),
    beginShapeIndex = json.intOr("begin_shape_index"),
    endShapeIndex = json.intOr("end_shape_index"),
)
