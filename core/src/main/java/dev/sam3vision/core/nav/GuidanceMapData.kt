package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import kotlin.math.cos
import kotlin.math.ln

/**
 * État carte du guidage, pur JVM : ce que la carte OSM dessine par-dessus les
 * tuiles (tracé, destination, position, traversées, pastilles) plus la
 * caméra (cible, zoom, cap).
 *
 */

/** Cible caméra : point centré, zoom et cap (0 = nord en haut). */
data class MapCamera(val target: RoutePoint, val zoom: Double, val bearingDeg: Float)

/** Manœuvre à afficher : point, libellé d'instruction, upcoming = prochaine. */
data class ManeuverMarker(val point: RoutePoint, val label: String, val upcoming: Boolean)

/** Tout ce que la carte dessine par-dessus les tuiles. */
data class MapOverlays(
    val route: List<RoutePoint>,
    val destination: RoutePoint?,
    val position: RoutePoint?,
    val accuracyM: Float?,
    val crossings: List<RoutePoint>,
    val maneuvers: List<ManeuverMarker>,
)

/** État complet : caméra nulle = cadrage automatique sur le contenu. */
data class GuidanceMapState(val camera: MapCamera?, val overlays: MapOverlays)

/** Résolution Web-Mercator au zoom 0 : mètres par pixel, tuiles 256 px (OSM). */
private const val OSM_METERS_PER_PIXEL_Z0 = 156543.03392

/** Largeur de viewport visée pour le cadrage (px, logique). */
private const val MAP_VIEWPORT_PX = 1000.0

/** Zoom rue (cadre ponctuel), plancher monde, plafond tuiles OSM. */
private const val ZOOM_STREET = 16.0
private const val ZOOM_MIN = 2.0
private const val ZOOM_MAX = 19.0

/**
 * Écart maximal affiché recalé, en mètres : miroir du défaut
 * [NavTuning.offRouteM] — au-delà, le moteur déclare l'écart ([NavState.OffRoute])
 * et l'affichage montre le fix brut (hors tracé) au lieu de le recaler.
 */
const val SNAP_DISPLAY_M = 30.0

/**
 * Tracé à dessiner : la `shape` Valhalla finie quand elle tient la route
 * (≥ 2 points), sinon les manœuvres finies (repli sans géométrie).
 * Route nulle ⇒ liste vide. Ne lève jamais.
 */
fun routePointsOf(route: NavRoute?): List<RoutePoint> {
    val shape = route?.shape.orEmpty().filter { it.lat.isFinite() && it.lon.isFinite() }
    if (shape.size >= 2) return shape
    return route?.maneuvers.orEmpty().mapNotNull { it.finitePoint() }
}

/**
 * Position à afficher : le fix exploitable ([NavFix.isUsable]) recalé sur
 * [shape] ([NavGeo.snapToPolyline]) quand l'écart reste sous [SNAP_DISPLAY_M],
 * le fix brut sinon (écarté : le moteur déclare l'écart, l'affichage le
 * montre). Fix inexploitable ou nul ⇒ `null`. Ne lève jamais.
 */
fun snappedPositionOf(fix: NavFix?, shape: List<RoutePoint>): RoutePoint? {
    val usable = fix?.takeIf { it.isUsable() } ?: return null
    val snap = NavGeo.snapToPolyline(usable.lat, usable.lon, shape)
    if (snap != null && snap.crossTrackM <= SNAP_DISPLAY_M) return snap.point
    return RoutePoint(usable.lat, usable.lon)
}

/**
 * Construit l'état carte depuis le guidage actif. Ne lève jamais (repli :
 * overlays vides, caméra nulle).
 *
 * - route nulle ou sans manœuvre → overlays vides et caméra nulle ;
 * - tracé = [routePointsOf], destination = dernier point ;
 * - position = [snappedPositionOf] (avec la précision du fix) ;
 * - traversées = manœuvres `crossing` finies ;
 * - une pastille par manœuvre finie, libellé [maneuverLabel] ;
 * - caméra : cadrage du tracé entier ([zoomFor], position incluse
 *   si connue), sinon `null` (cadrage auto) ; cap 0 (nord en haut).
 *
 * [update] est réservé : [GuidanceUpdate] ne porte aucun index de manœuvre
 * (ni instruction ciblée), donc `upcoming` vaut `false` partout — le moteur
 * devra exposer la prochaine manœuvre pour l'allumer.
 */
fun guidanceMapStateOf(
    route: NavRoute?,
    fix: NavFix?,
    update: GuidanceUpdate?,
): GuidanceMapState =
    runCatching { buildMapState(route, fix) }
        .getOrElse { GuidanceMapState(null, emptyOverlays()) }

/** Surcouche vide : hors guidage ou repli après exception. */
private fun emptyOverlays(): MapOverlays =
    MapOverlays(emptyList(), null, null, null, emptyList(), emptyList())

/** Assemblage (voir [guidanceMapStateOf]) ; l'appelant encaisse les exceptions. */
private fun buildMapState(
    route: NavRoute?,
    fix: NavFix?,
): GuidanceMapState {
    val maneuvers = route?.maneuvers.orEmpty()
    if (maneuvers.isEmpty()) return GuidanceMapState(null, emptyOverlays())
    val points = routePointsOf(route)
    val position = snappedPositionOf(fix, points)
    val overlays = MapOverlays(
        route = points,
        destination = points.lastOrNull(),
        position = position,
        accuracyM = fix?.takeIf { it.isUsable() }?.accM,
        crossings = maneuvers.filter { it.crossing }.mapNotNull { it.finitePoint() },
        maneuvers = maneuvers.mapNotNull { maneuver ->
            maneuver.finitePoint()?.let { point ->
                ManeuverMarker(point, maneuverLabel(maneuver, route?.destinationName), false)
            }
        },
    )
    return GuidanceMapState(cameraFor(points, position), overlays)
}

/**
 * Caméra d'ensemble : le tracé entier tient dans le cadre (centre +
 * [zoomFor]), position connue incluse (visible même hors tracé) ;
 * sinon `null` (cadrage auto). Cap 0 (nord en haut) dans tous les cas.
 */
private fun cameraFor(points: List<RoutePoint>, position: RoutePoint?): MapCamera? {
    val framed = if (position != null) points + position else points
    val target = boundsCenter(framed) ?: return null
    return MapCamera(target = target, zoom = zoomFor(framed), bearingDeg = 0f)
}

/** Centre du cadre englobant (miroir de la référence de [MapFit]). */
private fun boundsCenter(points: List<RoutePoint>): RoutePoint? {
    val bounds = mapBoundsOf(points) ?: return null
    return RoutePoint((bounds.minLat + bounds.maxLat) / 2.0, (bounds.minLon + bounds.maxLon) / 2.0)
}

/**
 * Zoom déduit de l'étendue du tracé — miroir de l'aspect-fit de [MapFit] :
 * 1. étendue métrique équirectangulaire (`cos` de la latitude moyenne,
 *    [METERS_PER_DEGREE]), comme [MapFit] ;
 * 2. `span` = max(est, nord) : la plus grande dimension cadre (aspect-fit sur
 *    canevas carré, comme `min(availW / spanE, availH / spanN)`) ;
 * 3. zoom continu Web-Mercator : `z = log2(156543,03 · cosRef · 1000 / span)`
 *    (le span tient dans 1000 px, tuiles OSM 256 px) ;
 * 4. cadre ponctuel (< 1 m) ou sans point fini → zoom rue 16 (miroir du repli
 *    dégénéré de [MapFit]) ; clamp [2, 19] ; non fini → 16. Ne lève jamais.
 */
private fun zoomFor(points: List<RoutePoint>): Double {
    val bounds = mapBoundsOf(points) ?: return ZOOM_STREET
    val latRef = (bounds.minLat + bounds.maxLat) / 2.0
    val cosRef = cos(latRef * Math.PI / 180.0)
    val spanEast =
        (bounds.maxLon - bounds.minLon).coerceAtLeast(0.0) * cosRef * METERS_PER_DEGREE
    val spanNorth =
        (bounds.maxLat - bounds.minLat).coerceAtLeast(0.0) * METERS_PER_DEGREE
    val span = maxOf(spanEast, spanNorth)
    if (!span.isFinite() || span < 1.0) return ZOOM_STREET
    val zoom = ln(OSM_METERS_PER_PIXEL_Z0 * cosRef * MAP_VIEWPORT_PX / span) / ln(2.0)
    if (!zoom.isFinite()) return ZOOM_STREET
    return zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
}

/** Point fini d'une manœuvre, ou `null` (pastille impossible). */
private fun NavManeuver.finitePoint(): RoutePoint? =
    if (lat.isFinite() && lon.isFinite()) RoutePoint(lat, lon) else null

/** Verbe français d'une action (pastille carte, sans la rue). */
private fun maneuverVerb(action: ManeuverAction): String = when (action) {
    ManeuverAction.Start -> "Départ"
    ManeuverAction.Continue -> "Continuez tout droit"
    ManeuverAction.SlightLeft -> "Légèrement à gauche"
    ManeuverAction.TurnLeft -> "Tournez à gauche"
    ManeuverAction.SlightRight -> "Légèrement à droite"
    ManeuverAction.TurnRight -> "Tournez à droite"
    ManeuverAction.UTurn -> "Faites demi-tour"
    ManeuverAction.Roundabout -> "Prenez le rond-point"
    ManeuverAction.Arrive -> "Arrivée"
}

/**
 * Libellé complet : verbe [maneuverVerb] + rue (`sur …`), départ et arrivée
 * avec `—` ; l'arrivée préfère [destinationName] à la rue. Rue blanche ou
 * absente : verbe seul (jamais inventé).
 */
private fun maneuverLabel(maneuver: NavManeuver, destinationName: String?): String {
    val verb = maneuverVerb(maneuver.action)
    val street = maneuver.street?.trim().orEmpty()
    val place = if (maneuver.action == ManeuverAction.Arrive) {
        destinationName?.trim().orEmpty().ifEmpty { street }
    } else {
        street
    }
    if (place.isEmpty()) return verb
    val joint = when (maneuver.action) {
        ManeuverAction.Start, ManeuverAction.Arrive -> " — "
        else -> " sur "
    }
    return verb + joint + place
}
