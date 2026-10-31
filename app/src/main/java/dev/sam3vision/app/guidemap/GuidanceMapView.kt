package dev.sam3vision.app.guidemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * Carte de guidage : fond vectoriel OpenFreeMap Liberty (sans clé), caméra
 * pilotée ou auto-cadrée sur le contenu (nulle), surcouches (tracé,
 * arrivée, position + précision, passages, manœuvres).
 * [onTilesError] est appelé une fois si le style ou les tuiles sont
 * injoignables. Ne lève jamais.
 * [showAttribution] `false` masque la mention légale (pages blind,
 * demande explicite) ; `true` par défaut partout ailleurs.
 *
 * Sans scintillement : le fond statique (tracé, pastilles) n'est redessiné
 * que quand il change ; à chaque tick, seule la position est rafraîchie et
 * la caméra ne bouge que si la cible a vraiment bougé (10 m) ou si le zoom
 * change — sinon le doigt de l'utilisateur serait arraché à chaque seconde.
 */
@Composable
fun GuidanceMapView(
    state: GuidanceMapState,
    modifier: Modifier = Modifier,
    onTilesError: () -> Unit = {},
    showAttribution: Boolean = true,
) {
    val mapView = rememberGuideMapView()
    GuideMapLifecycle(mapView)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf<GuidanceMapState?>(null) }
    val marks = remember { GuideMarks() }
    val appContext = LocalContext.current.applicationContext
    val pins = remember(appContext) { GuidePinIcons(appContext) }
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
            map = ready
            runCatching {
                ready.setStyle(Style.Builder().fromUrl(OPENFREEMAP_STYLE_URL)) { styleReady = true }
            }.onFailure { report() }
        }
    }
    val current = map
    val loaded = styleReady
    Box(modifier) {
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
            modifier = Modifier.fillMaxSize(),
        )
        if (showAttribution) GuideAttribution(Modifier.align(Alignment.BottomStart))
    }
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

/** Relaye le cycle Compose vers la MapView (surfaces libérées à la sortie). */
@Composable
private fun GuideMapLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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

/** Annotations vivantes (position + anneau), seules rafraîchies à chaque tick. */
private class GuideMarks(var position: Marker? = null, var ring: Polygon? = null)

/** Seuil de recentrage : en dessous, la caméra ne bouge pas (pas de tremblement). */
private const val CAMERA_MOVE_METERS = 10.0

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
        } else {
            marks.position?.let { runCatching { map.removeMarker(it) } }
            marks.ring?.let { runCatching { map.removePolygon(it) } }
        }
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
 * = punaise seule), références gardées pour le tick suivant.
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
        marks.position = null
        marks.ring = null
        return
    }
    val blue = pins.icon(AppColors.primary)
    marks.position = map.addMarker(MarkerOptions().position(pos.toLatLng()).title("Position").icon(blue))
    val accuracyM = overlays.accuracyM
    if (accuracyM == null || !accuracyM.isFinite() || accuracyM <= 0f) {
        marks.ring = null
        return
    }
    val ring = accuracyRing(pos, accuracyM).map { it.toLatLng() }
    if (ring.size < 4) {
        marks.ring = null
        return
    }
    val fill = AppColors.primary.toArgb()
    marks.ring = map.addPolygon(
        PolygonOptions().addAll(ring).fillColor(fill).alpha(0.25f).strokeColor(fill),
    )
}

/** Tracé bleu de l'itinéraire (2 points minimum, sinon rien). */
@Suppress("DEPRECATION")
private fun drawRoute(map: MapLibreMap, route: List<RoutePoint>) {
    val line = route.filter { it.isPlottable() }.map { it.toLatLng() }
    if (line.size < 2) return
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
