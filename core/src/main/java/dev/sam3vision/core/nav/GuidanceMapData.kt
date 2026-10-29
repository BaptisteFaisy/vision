package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import kotlin.math.cos
import kotlin.math.ln

/**
 * État carte du guidage, pur JVM : ce que la carte OSM dessine par-dessus les
 * tuiles (tracé, destination, position, traversées, feux, pastilles) plus la
 * caméra (cible, zoom, cap).
 *
 * Ces quatre types miroirent `app/guidemap/MapState.kt` à l'identique (mêmes
 * noms, mêmes champs) : `:core` ne peut pas importer `:app`, le parent
 * déduplique à l'intégration.
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
    val signals: List<RoutePoint>,
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
 * Construit l'état carte depuis le guidage actif. Ne lève jamais (repli :
 * overlays vides, caméra nulle).
 *
 * - route nulle ou sans manœuvre → overlays vides et caméra nulle ;
 * - tracé = manœuvres finies, destination = dernier point ;
 * - position = fix si [NavFix.isUsable] (avec sa précision) ;
 * - traversées = manœuvres `crossing` finies, signaux = feux finis ;
 * - une pastille par manœuvre finie, libellé [maneuverLabel] ;
 * - caméra : cible = position sinon centre du tracé, zoom [zoomFor], cap 0.
 *
 * [update] est réservé : [GuidanceUpdate] ne porte aucun index de manœuvre
 * (ni instruction ciblée), donc `upcoming` vaut `false` partout — le moteur
 * devra exposer la prochaine manœuvre pour l'allumer.
 */
fun guidanceMapStateOf(
    route: NavRoute?,
    fix: NavFix?,
    update: GuidanceUpdate?,
    signals: List<RoutePoint>,
): GuidanceMapState =
    runCatching { buildMapState(route, fix, signals) }
        .getOrElse { GuidanceMapState(null, emptyOverlays()) }

/** Surcouche vide : hors guidage ou repli après exception. */
private fun emptyOverlays(): MapOverlays =
    MapOverlays(emptyList(), null, null, null, emptyList(), emptyList(), emptyList())

/** Assemblage (voir [guidanceMapStateOf]) ; l'appelant encaisse les exceptions. */
private fun buildMapState(
    route: NavRoute?,
    fix: NavFix?,
    signals: List<RoutePoint>,
): GuidanceMapState {
    val maneuvers = route?.maneuvers.orEmpty()
    if (maneuvers.isEmpty()) return GuidanceMapState(null, emptyOverlays())
    val usableFix = fix?.takeIf { it.isUsable() }
    val position = usableFix?.let { RoutePoint(it.lat, it.lon) }
    val points = maneuvers.mapNotNull { it.finitePoint() }
    val overlays = MapOverlays(
        route = points,
        destination = points.lastOrNull(),
        position = position,
        accuracyM = usableFix?.accM,
        crossings = maneuvers.filter { it.crossing }.mapNotNull { it.finitePoint() },
        signals = signals.filter { it.lat.isFinite() && it.lon.isFinite() },
        maneuvers = maneuvers.mapNotNull { maneuver ->
            maneuver.finitePoint()?.let { point ->
                ManeuverMarker(point, maneuverLabel(maneuver, route?.destinationName), false)
            }
        },
    )
    return GuidanceMapState(cameraFor(points, position), overlays)
}

/**
 * Caméra : cible = position sinon centre du tracé, zoom [zoomFor], cap 0
 * (nord en haut). Sans cible (ni position ni point) : `null` (cadrage auto).
 */
private fun cameraFor(points: List<RoutePoint>, position: RoutePoint?): MapCamera? {
    val target = position ?: boundsCenter(points) ?: return null
    return MapCamera(target = target, zoom = zoomFor(points), bearingDeg = 0f)
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

/**
 * État carte du **mode navigation** (la carte sans destination) : position et
 * cercle de précision, feux connus autour, caméra au zoom rue sur la position.
 * Aucun itinéraire, aucune manœuvre — ce mode ne guide pas, il situe.
 *
 * Sans fix utilisable : overlays vides et caméra nulle (cadrage automatique).
 * Ne lève jamais (repli : état vide).
 */
fun mapModeMapStateOf(fix: NavFix?, signals: List<RoutePoint>): GuidanceMapState =
    runCatching { buildMapModeState(fix, signals) }
        .getOrElse { GuidanceMapState(null, emptyOverlays()) }

/** Assemblage du mode navigation (voir [mapModeMapStateOf]). */
private fun buildMapModeState(fix: NavFix?, signals: List<RoutePoint>): GuidanceMapState {
    val usable = fix?.takeIf { it.isUsable() }
    val position = usable?.let { RoutePoint(it.lat, it.lon) }
    return GuidanceMapState(
        camera = position?.let { MapCamera(target = it, zoom = ZOOM_STREET, bearingDeg = 0f) },
        overlays = emptyOverlays().copy(
            position = position,
            accuracyM = usable?.accM,
            signals = signals.filter { it.lat.isFinite() && it.lon.isFinite() },
        ),
    )
}
