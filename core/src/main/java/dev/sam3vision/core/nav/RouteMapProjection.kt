package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import kotlin.math.cos

/**
 * Projection schématique d'un itinéraire sur un canevas (carte de debug en
 * fond de l'écran iSee) : ajuste le cadre géo au canevas, nord en haut.
 *
 * Pur JVM : aucune dépendance Android ni Compose — le calque convertit
 * [MapPx] en `Offset`.
 */

/** Point projeté, en pixels dans le canevas. */
data class MapPx(val x: Float, val y: Float)

/** Cadre géo englobant, en degrés WGS84. */
data class MapBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

/** Mètres par degré de latitude (approximation sphérique, debug only). */
internal const val METERS_PER_DEGREE = 111_320.0

/**
 * Cadre englobant des points finis, ou `null` sans aucun point fini.
 * Ne lève jamais.
 */
fun mapBoundsOf(points: List<RoutePoint>): MapBounds? {
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    var count = 0
    for (point in points) {
        if (!point.lat.isFinite() || !point.lon.isFinite()) continue
        if (point.lat < minLat) minLat = point.lat
        if (point.lat > maxLat) maxLat = point.lat
        if (point.lon < minLon) minLon = point.lon
        if (point.lon > maxLon) maxLon = point.lon
        count++
    }
    if (count == 0) return null
    return MapBounds(minLat, maxLat, minLon, maxLon)
}

/**
 * Ajustement d'un [MapBounds] à un canevas : projection équirectangulaire
 * (longitude corrigée par `cos` de la latitude moyenne), homothétie uniforme
 * (aspect-fit), nord en haut. Cadre dégénéré (un seul point) : centré.
 */
class MapFit(
    bounds: MapBounds,
    private val widthPx: Float,
    private val heightPx: Float,
    paddingPx: Float,
) {
    private val latRef = (bounds.minLat + bounds.maxLat) / 2.0
    private val cosRef = cos(latRef * Math.PI / 180.0)
    private val rawEastM =
        (bounds.maxLon - bounds.minLon).coerceAtLeast(0.0) * cosRef * METERS_PER_DEGREE
    private val rawNorthM =
        (bounds.maxLat - bounds.minLat).coerceAtLeast(0.0) * METERS_PER_DEGREE

    /** Cadre ponctuel (< 1 cm) : tout point fini tombe au centre. */
    private val degenerate = rawEastM < 0.01 && rawNorthM < 0.01
    private val spanEastM = rawEastM.coerceAtLeast(1.0)
    private val spanNorthM = rawNorthM.coerceAtLeast(1.0)
    private val baseLat = bounds.maxLat
    private val baseLon = bounds.minLon

    /** Échelle uniforme (pixels par mètre), finie et strictement positive. */
    val pixelsPerMeter: Double

    /** Coin haut-gauche de la zone utile (contenu centré). */
    private val left: Double
    private val top: Double

    init {
        val availW = (widthPx - 2f * paddingPx).coerceAtLeast(1f).toDouble()
        val availH = (heightPx - 2f * paddingPx).coerceAtLeast(1f).toDouble()
        pixelsPerMeter = if (degenerate) {
            1.0
        } else {
            minOf(availW / spanEastM, availH / spanNorthM)
                .takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        }
        left = (widthPx.toDouble() - spanEastM * pixelsPerMeter) / 2.0
        top = (heightPx.toDouble() - spanNorthM * pixelsPerMeter) / 2.0
    }

    /**
     * Projette un point géo : nord en haut (`y` décroît quand `lat` croît).
     * Point non fini : centre du canevas. Ne lève jamais.
     */
    fun project(point: RoutePoint): MapPx {
        if (degenerate || !point.lat.isFinite() || !point.lon.isFinite()) {
            return MapPx(widthPx / 2f, heightPx / 2f)
        }
        val eastM = (point.lon - baseLon) * cosRef * METERS_PER_DEGREE
        val northM = (baseLat - point.lat) * METERS_PER_DEGREE
        return MapPx(
            (left + eastM * pixelsPerMeter).toFloat(),
            (top + northM * pixelsPerMeter).toFloat(),
        )
    }

    /** Rayon en pixels d'une distance en mètres (cercle de précision). */
    fun metersToPx(meters: Double): Float =
        (meters.coerceAtLeast(0.0) * pixelsPerMeter).toFloat()
}

/** Ajuste ce cadre à un canevas (voir [MapFit]). */
fun MapBounds.fit(widthPx: Float, heightPx: Float, paddingPx: Float): MapFit =
    MapFit(this, widthPx, heightPx, paddingPx)
