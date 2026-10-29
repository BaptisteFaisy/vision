package dev.sam3vision.app.guidemap

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapOverlays
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tuiles raster OSM France (style osmfr, libellés français), sans clé,
 * https uniquement. Trois sous-domaines (a, b, c) : le SDK les tourne pour
 * paralléliser. CARTO écarté : clé API exigée depuis 2026 (filigrane sinon).
 */
const val OSM_TILE_URL_A = "https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png"
const val OSM_TILE_URL_B = "https://b.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png"
const val OSM_TILE_URL_C = "https://c.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png"

/** Les trois miroirs, dans l'ordre du style. */
val OSM_TILE_URLS = listOf(OSM_TILE_URL_A, OSM_TILE_URL_B, OSM_TILE_URL_C)

/** Mention légale courte, affichée en surimpression permanente sur la carte. */
const val OSM_ATTRIBUTION_SHORT = "© OpenStreetMap contributors"

/** Mention légale longue (source du style, dialogue du SDK). */
const val OSM_ATTRIBUTION_LONG =
    "© les contributeurs OpenStreetMap (ODbL) — fond : OpenStreetMap France (CC BY-SA)"

/** Zoom natif max des tuiles osmfr (au-delà, sur-échantillonnage client). */
const val OSM_MAX_ZOOM = 20

/** Zoom client max du calque raster (spec de style v8 : 22 par défaut). */
const val OSM_LAYER_MAX_ZOOM = 22

/** Mètres par degré de latitude (sphère, même approximation que le core). */
private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Centre de secours du cadrage auto sans contenu : Paris (tuiles IdF). */
val GUIDE_DEFAULT_TARGET = RoutePoint(lat = 48.8566, lon = 2.3522)

/** Zoom de secours du cadrage auto sans contenu : Paris en entier. */
const val GUIDE_DEFAULT_ZOOM = 11.0

/** Zoom du cadrage auto sur un contenu ponctuel : pâté de maisons. */
const val GUIDE_SINGLE_POINT_ZOOM = 16.0

/**
 * Style MapLibre local (JSON version 8) : une seule source raster OSM
 * France, aucune clé, aucune URL venue des réglages. Pur JVM, testé sans
 * appareil.
 */
object GuideMapStyle {
    /** Construit le JSON de style (raster plein écran, zoom 0 au max client). */
    fun styleJson(): String {
        val tiles = OSM_TILE_URLS.joinToString(", ") { "\"$it\"" }
        return """
        {
          "version": 8,
          "name": "iSee OSM",
          "sources": {
            "osm": {
              "type": "raster",
              "tiles": [$tiles],
              "tileSize": 256,
              "maxzoom": $OSM_MAX_ZOOM,
              "attribution": "$OSM_ATTRIBUTION_LONG"
            }
          },
          "layers": [
            {"id": "osm", "type": "raster", "source": "osm", "minzoom": 0, "maxzoom": $OSM_LAYER_MAX_ZOOM}
          ]
        }
        """.trimIndent()
    }

    /** URL d'une tuile (z, x, y) — premier miroir du style. */
    fun tileUrl(z: Int, x: Int, y: Int): String =
        OSM_TILE_URL_A
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
}

/**
 * Vrai si le point passe les garde-fous du SDK (`LatLng` lève sur NaN,
 * infini ou hors bornes WGS84) : filtrer avant toute conversion.
 */
fun RoutePoint.isPlottable(): Boolean =
    lat.isFinite() && lon.isFinite() &&
        lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0

/**
 * Tous les points à cadrer : tracé, arrivée, position, passages, feux,
 * manœuvres — nuls écartés, non traçables écartés.
 */
fun collectFramePoints(overlays: MapOverlays): List<RoutePoint> =
    buildList {
        addAll(overlays.route)
        overlays.destination?.let { add(it) }
        overlays.position?.let { add(it) }
        addAll(overlays.crossings)
        addAll(overlays.signals)
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
