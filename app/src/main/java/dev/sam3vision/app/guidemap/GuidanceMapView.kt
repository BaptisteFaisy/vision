package dev.sam3vision.app.guidemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.GuidanceMapState
import dev.sam3vision.core.nav.MapCamera
import dev.sam3vision.core.nav.MapOverlays
import dev.sam3vision.core.nav.mapBoundsOf
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Carte de guidage : fond vectoriel OpenFreeMap Positron (sans clé), caméra
 * pilotée ou auto-cadrée sur le contenu (nulle), surcouches (tracé,
 * arrivée, position + précision, passages, manœuvres).
 * [onTilesError] est appelé une fois si le style ou les tuiles sont
 * injoignables. Ne lève jamais.
 * [showAttribution] `false` masque la mention légale (pages blind,
 * demande explicite) ; `true` par défaut partout ailleurs.
 *
 * Sans scintillement : le fond statique (tracé, pastilles) n'est redessiné
 * que quand il change ; à chaque tick, seule la position est rafraîchie —
 * annotations déplacées (setPosition/setPoints), jamais supprimées puis
 * recréées — et la caméra ne bouge que si la cible a vraiment bougé (2 m)
 * ou si le zoom change — sinon le doigt de l'utilisateur serait arraché à
 * chaque seconde.
 *
 * Coins arrondis ([GuideMapCornerRadius]) : la MapView rend sur une
 * SurfaceView (surface dédiée, insensible au `clip` Compose) — les coins
 * sont donc repeints à [cornerColor], qui doit être le fond uni derrière
 * la carte (blanc sur l'accueil iSee) sous peine de carrés visibles.
 */
@Composable
fun GuidanceMapView(
    state: GuidanceMapState,
    modifier: Modifier = Modifier,
    onTilesError: () -> Unit = {},
    showAttribution: Boolean = true,
    cornerColor: Color,
) {
    val mapView = rememberGuideMapView()
    GuideMapLifecycle(mapView)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf<GuidanceMapState?>(null) }
    val marks = remember { GuideMarks() }
    val appContext = LocalContext.current.applicationContext
    val pins = remember(appContext) { GuidePinIcons(appContext) }
    GuideMapLoader(mapView, onMap = { map = it }, onStyle = { styleReady = true }, onTilesError = onTilesError)
    val current = map
    val loaded = styleReady
    Box(modifier.shadow(6.dp, RoundedCornerShape(GuideMapCornerRadius))) {
        AndroidView(
            factory = { mapView },
            update = { view ->
                val target = current
                if (target != null && loaded && applied != state) {
                    val prev = applied
                    applied = state
                    applyGuideState(target, view, prev, state, marks, pins)
                }
            },
            onRelease = ::releaseGuideMap,
            modifier = Modifier.fillMaxSize(),
        )
        // Masque composé APRÈS l'AndroidView : le Compose d'avant passerait
        // derrière la carte (vraie View, voir docs/MASQUES-DEBUG.md).
        GuideMapCorners(cornerColor, Modifier.fillMaxSize())
        GuideVignette(Modifier.fillMaxSize())
        if (showAttribution) GuideAttribution(Modifier.align(Alignment.BottomStart))
    }
}

/**
 * Sortie d'écran : la MapView est détruite (sans ça, la GL fuyait et les
 * guidages suivants affichaient une carte figée). Ne lève jamais.
 */
private fun releaseGuideMap(view: MapView) {
    runCatching { view.onPause() }
    runCatching { view.onStop() }
    runCatching { view.onDestroy() }
}

/** MapView mémorisée : SDK initialisé (sans clé) avant création, cycle ouvert. */
@Composable
private fun rememberGuideMapView(): MapView {
    val context = LocalContext.current
    return remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
}

/**
 * Relaye le cycle Compose vers la MapView (surfaces libérées à la sortie).
 * Rattrapage à l'attache : la carte composée alors que le cycle est déjà
 * démarré ne recevrait jamais `ON_START`/`ON_RESUME` (pas de rejeu) et
 * resterait noire — la vue est donc amenée à l'état courant aussitôt.
 */
@Composable
private fun GuideMapLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) runCatching { mapView.onStart() }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { mapView.onResume() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                Lifecycle.Event.ON_DESTROY -> runCatching { mapView.onDestroy() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * Charge la carte et son style Positron ; signale l'échec des tuiles une
 * fois. Ne lève jamais (l'appelant bascule sur le repli schéma).
 */
@Composable
private fun GuideMapLoader(
    mapView: MapView,
    onMap: (MapLibreMap) -> Unit,
    onStyle: () -> Unit,
    onTilesError: () -> Unit,
) {
    LaunchedEffect(mapView) {
        var sent = false
        fun report() {
            if (!sent) {
                sent = true
                onTilesError()
            }
        }
        mapView.addOnDidFailLoadingMapListener { report() }
        mapView.getMapAsync { ready ->
            onMap(ready)
            runCatching {
                ready.setStyle(Style.Builder().fromUrl(OPENFREEMAP_STYLE_URL)) { onStyle() }
            }.onFailure { report() }
        }
    }
}

/**
 * Mention légale OpenFreeMap/OpenMapTiles/OSM en bas à gauche (contrat
 * du fond gratuit : attribution obligatoire, masquée sur les pages
 * blind uniquement, sur demande explicite).
 */
@Composable
private fun GuideAttribution(modifier: Modifier = Modifier) {
    Text(
        text = OSM_ATTRIBUTION_SHORT,
        modifier = modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
        color = Color.White,
        fontSize = 11.sp,
    )
}

/**
 * Annotations vivantes (position + anneau), seules rafraîchies à chaque
 * tick : gardées d'un tick à l'autre pour être déplacées, pas recréées.
 */
private class GuideMarks(var position: Marker? = null, var ring: Polygon? = null)

/** Seuil de recentrage : en dessous, la caméra ne bouge pas (pas de tremblement). */
private const val CAMERA_MOVE_METERS = 2.0

/** Opacité de l'anneau de précision (identique au rendu d'origine). */
private const val RING_ALPHA = 0.25f

/** Pastille des punaises : diamètre du bitmap, épaisseur du liseré blanc. */
private const val PIN_SIZE_PX = 48
private const val PIN_RING_PX = 5f

/**
 * Pastilles de couleur des punaises : un bitmap par couleur, cachés
 * (IconFactory + MarkerOptions, annotations classiques du SDK).
 */
private class GuidePinIcons(context: Context) {
    private val factory = IconFactory.getInstance(context)
    private val cache = mutableMapOf<Int, Icon>()

    /** Pastille de la couleur [color] (créée une fois, réutilisée ensuite). */
    fun icon(color: Color): Icon =
        cache.getOrPut(color.toArgb()) { factory.fromBitmap(pinBitmap(color.toArgb())) }
}

/** Pastille : disque couleur cerclé de blanc, lisible sur tout fond. */
private fun pinBitmap(colorArgb: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(PIN_SIZE_PX, PIN_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = PIN_SIZE_PX / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(radius, radius, radius, paint)
    paint.color = colorArgb
    canvas.drawCircle(radius, radius, radius - PIN_RING_PX, paint)
    return bitmap
}

/** Caméra puis surcouches, sans jamais lever (jamais de crash carte). */
private fun applyGuideState(
    map: MapLibreMap,
    view: MapView,
    prev: GuidanceMapState?,
    state: GuidanceMapState,
    marks: GuideMarks,
    pins: GuidePinIcons,
) {
    val staticChanged = prev == null || prev.overlays.staticPart() != state.overlays.staticPart()
    if (staticChanged || shouldMoveCamera(prev?.camera, state.camera)) {
        runCatching { applyGuideCamera(map, view, state) }
    }
    runCatching {
        if (staticChanged) {
            map.clear()
            marks.position = null
            marks.ring = null
            drawGuideStatic(map, state.overlays, pins)
        }
        // Plus de removeMarker/removePolygon par tick : les annotations vivantes
        // sont réutilisées telles quelles (setPosition/setPoints), donc aucun
        // upload de texture d'icône ni de géométrie neuve à chaque seconde.
        drawGuidePosition(map, state.overlays, marks, pins)
    }
}

/** Partie statique des surcouches : tout sauf la position vivante. */
private fun MapOverlays.staticPart(): MapOverlays = copy(position = null, accuracyM = null)

/**
 * Vrai s'il faut bouger la caméra : première fois, cadrage auto, zoom ou
 * cap changé, ou cible déplacée de plus de [CAMERA_MOVE_METERS] — sinon le
 * cadre tremblerait à chaque tick GPS.
 */
private fun shouldMoveCamera(prev: MapCamera?, next: MapCamera?): Boolean {
    if (next == null || prev == null) return true
    if (next.zoom != prev.zoom || next.bearingDeg != prev.bearingDeg) return true
    return metersBetween(prev.target, next.target) > CAMERA_MOVE_METERS
}

/** Distance approximative (équirectangulaire) entre deux points, en mètres. */
private fun metersBetween(a: RoutePoint, b: RoutePoint): Double {
    if (!a.isPlottable() || !b.isPlottable()) return Double.MAX_VALUE
    val cosRef = kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2.0)).coerceAtLeast(0.01)
    val dLat = (a.lat - b.lat) * 111_320.0
    val dLon = (a.lon - b.lon) * cosRef * 111_320.0
    return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}

/** Caméra pilotée, sinon cadrage auto sur le contenu, sinon Paris. */
private fun applyGuideCamera(map: MapLibreMap, view: MapView, state: GuidanceMapState) {
    val camera = state.camera
    if (camera != null && camera.target.isPlottable() && camera.zoom.isFinite()) {
        map.cameraPosition = CameraPosition.Builder()
            .target(camera.target.toLatLng())
            .zoom(camera.zoom.coerceIn(0.0, 22.0))
            .bearing(camera.bearingDeg.toDouble())
            .build()
        return
    }
    val update = frameUpdate(state.overlays, view) ?: return
    runCatching { map.moveCamera(update) }
        .onFailure { view.post { runCatching { map.moveCamera(update) } } }
}

/** Cadrage de secours : Paris, nord en haut. */
private fun defaultUpdate(): CameraUpdate =
    CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder().target(GUIDE_DEFAULT_TARGET.toLatLng()).zoom(GUIDE_DEFAULT_ZOOM).build(),
    )

/** Update de cadrage auto : bornes du contenu, point seul, ou défaut Paris. */
private fun frameUpdate(overlays: MapOverlays, view: MapView): CameraUpdate? {
    val points = collectFramePoints(overlays)
    if (points.isEmpty()) return defaultUpdate()
    val bounds = mapBoundsOf(points) ?: return defaultUpdate()
    val single = bounds.maxLat - bounds.minLat < 1e-6 && bounds.maxLon - bounds.minLon < 1e-6
    if (single) {
        val center = RoutePoint((bounds.minLat + bounds.maxLat) / 2.0, (bounds.minLon + bounds.maxLon) / 2.0)
        return CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(center.toLatLng()).zoom(GUIDE_SINGLE_POINT_ZOOM).build(),
        )
    }
    val box = LatLngBounds.Builder()
        .include(LatLng(bounds.minLat, bounds.minLon))
        .include(LatLng(bounds.maxLat, bounds.maxLon))
        .build()
    val padding = (48f * view.resources.displayMetrics.density).toInt()
    return CameraUpdateFactory.newLatLngBounds(box, padding)
}

/**
 * Redessine le statique : tracé, arrivée (vert succès), passages et
 * manœuvres (gris-bleu neutre). Annotations classiques (dépréciées mais
 * livrées) : fondation simple, le plugin d'annotations viendra si besoin.
 */
@Suppress("DEPRECATION")
private fun drawGuideStatic(map: MapLibreMap, overlays: MapOverlays, pins: GuidePinIcons) {
    drawRoute(map, overlays.route)
    overlays.destination?.let { drawPin(map, it, "Arrival", pins.icon(AppColors.success)) }
    val neutral = pins.icon(AppColors.textMuted)
    overlays.crossings.forEach { drawPin(map, it, "Crosswalk", neutral) }
    overlays.maneuvers.forEach { drawPin(map, it.point, it.label, neutral) }
}

/**
 * Position vivante : punaise bleue + disque de précision (rayon invalide
 * = punaise seule). Les annotations du tick précédent sont déplacées sur
 * place (voir [movePositionMarker] et [updateAccuracyRing]) au lieu d'être
 * supprimées puis recréées.
 */
@Suppress("DEPRECATION")
private fun drawGuidePosition(
    map: MapLibreMap,
    overlays: MapOverlays,
    marks: GuideMarks,
    pins: GuidePinIcons,
) {
    val pos = overlays.position
    if (pos == null || !pos.isPlottable()) {
        dropPositionMarks(map, marks)
        return
    }
    movePositionMarker(map, pos.toLatLng(), marks, pins)
    updateAccuracyRing(map, pos, overlays.accuracyM, marks)
}

/**
 * Punaise de position : si elle existe déjà, `Marker.setPosition(LatLng)`
 * (présente en MapLibre 13.7.1, vérifiée dans l'AAR) réécrit la géométrie
 * sans recréer l'annotation — l'icône n'est donc jamais re-téléversée.
 * Créée seulement au premier tick. Titre et icône ne changent jamais.
 */
@Suppress("DEPRECATION")
private fun movePositionMarker(
    map: MapLibreMap,
    latLng: LatLng,
    marks: GuideMarks,
    pins: GuidePinIcons,
) {
    val existing = marks.position
    if (existing != null) {
        existing.position = latLng
        return
    }
    marks.position = map.addMarker(
        MarkerOptions().position(latLng).title("Position").icon(pins.icon(AppColors.primary)),
    )
}

/**
 * Anneau de précision : `Polygon.setPoints(List<LatLng>)` (héritée de
 * BasePointCollection, présente en 13.7.1) réécrit les points sur place.
 * On ne recrée l'annotation que si le style change (fillColor/alpha
 * différents de l'anneau courant), car ces setters relancent un update
 * natif complet de l'annotation.
 */
@Suppress("DEPRECATION")
private fun updateAccuracyRing(
    map: MapLibreMap,
    center: RoutePoint,
    accuracyM: Float?,
    marks: GuideMarks,
) {
    val points = accuracyRingPoints(center, accuracyM)
    if (points == null) {
        dropRing(map, marks)
        return
    }
    val fill = AppColors.primary.toArgb()
    val ring = marks.ring
    if (ring != null && ring.fillColor == fill && ring.alpha == RING_ALPHA) {
        ring.points = points
        return
    }
    if (ring != null) runCatching { map.removePolygon(ring) }
    marks.ring = map.addPolygon(
        PolygonOptions().addAll(points).fillColor(fill).alpha(RING_ALPHA).strokeColor(fill),
    )
}

/** Points de l'anneau de précision, ou nul si le rayon est inutilisable. */
private fun accuracyRingPoints(center: RoutePoint, accuracyM: Float?): List<LatLng>? {
    if (accuracyM == null || !accuracyM.isFinite() || accuracyM <= 0f) return null
    return accuracyRing(center, accuracyM).map { it.toLatLng() }.takeIf { it.size >= 4 }
}

/** Position perdue : les deux annotations vivantes quittent la carte. */
@Suppress("DEPRECATION")
private fun dropPositionMarks(map: MapLibreMap, marks: GuideMarks) {
    marks.position?.let { runCatching { map.removeMarker(it) } }
    marks.position = null
    dropRing(map, marks)
}

/** Anneau retiré (rayon inutilisable) : rien à réutiliser au tick suivant. */
@Suppress("DEPRECATION")
private fun dropRing(map: MapLibreMap, marks: GuideMarks) {
    marks.ring?.let { runCatching { map.removePolygon(it) } }
    marks.ring = null
}

/** Tracé bleu de l'itinéraire à liseré blanc (2 points minimum, sinon rien). */
@Suppress("DEPRECATION")
private fun drawRoute(map: MapLibreMap, route: List<RoutePoint>) {
    val line = route.filter { it.isPlottable() }.map { it.toLatLng() }
    if (line.size < 2) return
    map.addPolyline(PolylineOptions().addAll(line).color(Color.White.toArgb()).width(9f))
    map.addPolyline(PolylineOptions().addAll(line).color(AppColors.primary.toArgb()).width(5f))
}

/** Punaise titrée colorée (point non traçable = rien, jamais de plantage). */
@Suppress("DEPRECATION")
private fun drawPin(map: MapLibreMap, point: RoutePoint, title: String, icon: Icon) {
    if (!point.isPlottable()) return
    map.addMarker(MarkerOptions().position(point.toLatLng()).title(title).icon(icon))
}

/** Conversion WGS84 → SDK (n'appeler que sur point traçable). */
private fun RoutePoint.toLatLng(): LatLng = LatLng(lat, lon)
