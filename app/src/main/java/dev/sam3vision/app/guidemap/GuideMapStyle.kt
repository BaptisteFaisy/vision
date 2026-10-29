package dev.sam3vision.app.guidemap

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapOverlays
import kotlin.math.cos
import kotlin.math.sin

/**
 * Style vectoriel OpenFreeMap Liberty (look moderne type Google Maps),
 * chargé par URL directe — sans clé, https uniquement. Le style Liberty
 * est la déclinaison « rues » d'OpenFreeMap (variantes : positron, bright,
 * dark, fiord).
 */
const val OPENFREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Mention légale courte, affichée en surimpression permanente sur la carte. */
const val OSM_ATTRIBUTION_SHORT = "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"

/** Mention légale longue (modèle du pied de page openfreemap.org). */
const val OSM_ATTRIBUTION_LONG =
    "© OpenMapTiles, © les contributeurs OpenStreetMap (ODbL) — fond vectoriel : OpenFreeMap (style Liberty)"

/** Mètres par degré de latitude (sphère, même approximation que le core). */
private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Centre de secours du cadrage auto sans contenu : Paris (tuiles IdF). */
val GUIDE_DEFAULT_TARGET = RoutePoint(lat = 48.8566, lon = 2.3522)

/** Zoom de secours du cadrage auto sans contenu : Paris en entier. */
const val GUIDE_DEFAULT_ZOOM = 11.0

/** Zoom du cadrage auto sur un contenu ponctuel : pâté de maisons. */
const val GUIDE_SINGLE_POINT_ZOOM = 16.0

/**
 * Vrai si le point passe les garde-fous du SDK (`LatLng` lève sur NaN,
 * infini ou hors bornes WGS84) : filtrer avant toute conversion.
 */
fun RoutePoint.isPlottable(): Boolean =
    lat.isFinite() && lon.isFinite() &&
        lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0

/**
 * Tous les points à cadrer : tracé, arrivée, position, passages,
 * manœuvres — nuls écartés, non traçables écartés.
 */
fun collectFramePoints(overlays: MapOverlays): List<RoutePoint> =
    buildList {
        addAll(overlays.route)
        overlays.destination?.let { add(it) }
        overlays.position?.let { add(it) }
        addAll(overlays.crossings)
        overlays.maneuvers.forEach { add(it.point) }
    }.filter { it.isPlottable() }

/**
 * Anneau du cercle de précision : [steps] segments + fermeture, degrés
 * corrigés en longitude par le cosinus de la latitude. Vide si le centre
 * ou le rayon est invalide. Pur JVM, testé sans appareil.
 */
fun accuracyRing(center: RoutePoint, radiusMeters: Float, steps: Int = 32): List<RoutePoint> {
    if (!center.isPlottable() || !radiusMeters.isFinite() || radiusMeters <= 0f || steps < 3) {
        return emptyList()
    }
    val cosRef = cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
    val dLat = radiusMeters / METERS_PER_DEGREE_LAT
    val dLon = radiusMeters / (METERS_PER_DEGREE_LAT * cosRef)
    return List(steps + 1) { i ->
        val angle = 2.0 * Math.PI * i / steps
        RoutePoint(center.lat + dLat * sin(angle), center.lon + dLon * cos(angle))
    }
}
