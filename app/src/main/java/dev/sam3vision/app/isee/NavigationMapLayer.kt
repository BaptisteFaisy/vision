package dev.sam3vision.app.isee

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import dev.sam3vision.app.guidemap.GuidanceMapView
import dev.sam3vision.app.voicenav.MapPositionWatcher
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapBounds
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.fit
import dev.sam3vision.core.nav.mapBoundsAround
import dev.sam3vision.core.nav.mapModeMapStateOf
import dev.sam3vision.core.net.HttpSignalsClient
import kotlinx.coroutines.delay

/** Rayon du schéma de repli : 200 m autour de la position. */
internal const val NAV_MAP_RADIUS_M = 200.0

/** Maille de rechargement des feux : 0,001° ≈ 110 m de déplacement. */
private const val SIGNALS_CELL = 1000.0

/** Attente avant de redemander les feux après un échec réseau. */
private const val SIGNALS_RETRY_MS = 60_000L

private val MapSignal = Color(0xFFD92D20)
private val MapPosition = Color(0xFF2F6FED)
private val MapAccuracy = Color(0xFF2F6FED).copy(alpha = 0.18f)

/**
 * Carte du **mode navigation**, posée **sous la caméra** : première couche de
 * la scène iSee, plein écran, elle apparaît dès que la session est en
 * [VoiceNavUiState.Map] (ordre vocal « navigation », « carte », « où suis-je »)
 * et **s'efface pendant que l'utilisateur parle** ([navigationMapHidden]) — la
 * voix reprend alors l'écran, sans bouton ni carte à lire.
 *
 * Fond OSM MapLibre, identique à l'écran de guidage ; tuiles en échec → schéma
 * maison (position, cercle de précision, feux), même contrat de repli. La
 * position vient de [MapPositionWatcher] (GPS + réseau, 2 s), distinct du
 * pilote d'itinéraire : la carte n'a pas de destination à calculer.
 */
@Composable
fun NavigationMapLayer(viewModel: IseeViewModel, state: IseeUiState, modifier: Modifier = Modifier) {
    val navState by viewModel.voiceNavState.collectAsState()
    if (navState !is VoiceNavUiState.Map) return
    val context = LocalContext.current
    var fix by remember { mutableStateOf<NavFix?>(null) }
    DisposableEffect(Unit) {
        val watcher = MapPositionWatcher(context) { fix = it }
        watcher.start()
        onDispose { watcher.stop() }
    }
    val signals = rememberNavigationSignals(viewModel, fix)
    val mapState = remember(fix, signals) { mapModeMapStateOf(fix, signals) }
    var tilesOk by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        if (navigationMapHidden(state)) 0f else 1f,
        tween(220),
        label = "navigation-map",
    )
    Box(modifier.graphicsLayer { this.alpha = alpha }) {
        if (tilesOk) {
            GuidanceMapView(mapState, Modifier.fillMaxSize(), onTilesError = { tilesOk = false })
        } else {
            Canvas(Modifier.fillMaxSize()) { drawNavigationMap(viewBoundsOf(fix), fix, signals) }
        }
    }
}

/**
 * La carte s'efface pendant que l'utilisateur parle : tenue du micro (il
 * dicte) ou transcription en cours. iSee qui parle ne la masque pas — c'est
 * la voix de l'utilisateur qui veut l'écran. Pure, testée en JVM.
 */
fun navigationMapHidden(state: IseeUiState): Boolean =
    state.holding || state.transcribing

/**
 * Feux du cadre affiché, rechargés quand la position sort d'une maille de
 * [SIGNALS_CELL] (≈ 110 m) et non à chaque point ; échec = liste vide, nouvel
 * essai après [SIGNALS_RETRY_MS]. Sans URL de routeur : aucune requête.
 */
@Composable
private fun rememberNavigationSignals(viewModel: IseeViewModel, fix: NavFix?): List<RoutePoint> {
    var signals by remember { mutableStateOf(emptyList<RoutePoint>()) }
    val client = remember { HttpSignalsClient(viewModel::routeBaseUrl) }
    val cell = fix?.let { (it.lat * SIGNALS_CELL).toInt() to (it.lon * SIGNALS_CELL).toInt() }
    LaunchedEffect(cell) {
        val here = fix ?: return@LaunchedEffect
        if (viewModel.routeBaseUrl().isBlank()) return@LaunchedEffect
        val bounds = mapBoundsAround(RoutePoint(here.lat, here.lon), NAV_MAP_RADIUS_M)
        signals = client.signals(bounds).getOrElse {
            delay(SIGNALS_RETRY_MS)
            emptyList()
        }
    }
    return signals
}

/** Cadre du schéma de repli : 200 m autour de la position, ou cadre dégénéré. */
internal fun viewBoundsOf(fix: NavFix?): MapBounds =
    if (fix == null) {
        MapBounds(0.002, 0.002, 0.002, 0.002)
    } else {
        mapBoundsAround(RoutePoint(fix.lat, fix.lon), NAV_MAP_RADIUS_M)
    }

/** Dessin du repli : feux, cercle de précision, position — aucun état. */
private fun DrawScope.drawNavigationMap(bounds: MapBounds, fix: NavFix?, signals: List<RoutePoint>) {
    val fit = bounds.fit(size.width, size.height, 48f)
    for (signal in signals) {
        val px = fit.project(signal)
        val center = Offset(px.x, px.y)
        drawCircle(Color.White, radius = 8f, center = center)
        drawCircle(MapSignal, radius = 6f, center = center)
    }
    if (fix == null) return
    val here = fit.project(RoutePoint(fix.lat, fix.lon))
    val center = Offset(here.x, here.y)
    drawCircle(MapAccuracy, radius = fit.metersToPx(fix.accM.toDouble()).coerceAtLeast(6f), center = center)
    drawCircle(Color.White, radius = 13f, center = center)
    drawCircle(MapPosition, radius = 9f, center = center)
}
