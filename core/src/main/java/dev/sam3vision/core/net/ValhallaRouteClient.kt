package dev.sam3vision.core.net

import org.json.JSONObject

/** Coût piéton : le seul mode de la navigation vocale iSee. */
const val COSTING_PEDESTRIAN = "pedestrian"

/** Point géographique (degrés décimaux WGS84). */
data class LatLon(val lat: Double, val lon: Double)

/** Une étape d'itinéraire : consigne Valhalla telle quelle + longueur + durée. */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val timeSeconds: Double,
)

/**
 * Digest d'itinéraire : totaux + étapes dans l'ordre, sans le tracé.
 * Sert l'écran de confirmation (distance · durée) ; le guidage utilise le
 * modèle riche ([dev.sam3vision.core.model.RouteTrip], tracé + manœuvres).
 */
data class RouteDigest(
    val distanceMeters: Double,
    val timeSeconds: Double,
    val steps: List<RouteStep>,
)

/**
 * Corps `POST /route` : deux points + coût. Pur (testé JVM, sans réseau).
 * Valhalla attend `locations` avec `lat`/`lon` et un `costing` (« pedestrian »).
 */
internal fun buildRouteRequestJson(from: LatLon, to: LatLon, costing: String): String =
    JSONObject()
        .put("locations", listOf(pointJson(from), pointJson(to)))
        .put("costing", costing)
        .toString()

private fun pointJson(point: LatLon): JSONObject =
    JSONObject().put("lat", point.lat).put("lon", point.lon)

/**
 * Réponse `POST /route` → [RouteDigest]. Pur (testé JVM) et tolérant : champs
 * absents = zéro / liste vide, jamais d'exception — un itinéraire partiel reste
 * annonçable. `length` Valhalla est en kilomètres, `time` en secondes.
 * Le modèle riche strict est [parseRouteTrip] (`RouteClient.kt`).
 */
internal fun parseRouteDigest(raw: String): Result<RouteDigest> = catchingResult {
    val trip = JSONObject(raw).optJSONObject("trip")
        ?: throw ApiError.BadResponse("/route: route missing")
    val summary = trip.optJSONObject("summary")
    RouteDigest(
        distanceMeters = summary.kmToMeters("length"),
        timeSeconds = summary?.optDouble("time", 0.0) ?: 0.0,
        steps = trip.objects("legs").flatMap { leg -> leg.objects("maneuvers").map { it.routeStep() } },
    )
}

private fun JSONObject?.kmToMeters(key: String): Double =
    (this?.optDouble(key, 0.0) ?: 0.0) * 1000.0

private fun JSONObject.routeStep(): RouteStep = RouteStep(
    instruction = str("instruction"),
    distanceMeters = kmToMeters("length"),
    timeSeconds = optDouble("time", 0.0),
)

