package dev.sam3vision.app.isee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapPx
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.fit
import dev.sam3vision.core.nav.mapBoundsOf
import kotlinx.coroutines.delay

/** Rafraîchissement de la carte de debug (position GPS à 1 Hz). */
private const val DEBUG_MAP_POLL_MS = 1_000L

private val MapRoute = Color(0xFF7FA8D9)
private val MapRouteDot = Color(0xFF5B87C7)
private val MapDestination = Color(0xFFD97F7F)
private val MapPosition = Color(0xFF2F6FED)
private val MapAccuracy = Color(0xFF2F6FED).copy(alpha = 0.12f)

/** Instantané debug : tracé de l'itinéraire guidé + position GPS. */
private data class DebugRouteSnapshot(
    val points: List<RoutePoint>,
    val position: RoutePoint?,
    val accuracyM: Float?,
)

/**
 * Carte de debug en fond de l'écran iSee : tracé schématique de l'itinéraire
 * (un segment par manœuvre), pastille d'arrivée, position GPS + cercle de
 * précision. Première couche de la scène (derrière la rue, la boule et les
 * textes) : elle ne gêne jamais l'évitement d'obstacles.
 *
 * Rien n'est composé quand le réglage est coupé, hors guidage, ou sans
 * itinéraire — l'écran est alors exactement comme avant.
 */
@Composable
fun DebugRouteMapLayer(debugEnabled: Boolean, viewModel: IseeViewModel, modifier: Modifier = Modifier) {
    if (!debugEnabled) return
    val navState by viewModel.voiceNavState.collectAsState()
    if (navState !is VoiceNavUiState.Guiding) return
    var snapshot by remember { mutableStateOf<DebugRouteSnapshot?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val (route, fix) = viewModel.debugNavSnapshot()
            snapshot = snapshotOf(route, fix)
            delay(DEBUG_MAP_POLL_MS)
        }
    }
    val snap = snapshot ?: return
    Canvas(modifier.fillMaxSize()) { drawDebugRoute(snap) }
}

/** Tracé + position à partir de l'itinéraire guidé ; `null` sans point fini. */
private fun snapshotOf(route: NavRoute?, fix: NavFix?): DebugRouteSnapshot? {
    val points = route?.maneuvers.orEmpty()
        .map { RoutePoint(it.lat, it.lon) }
        .filter { it.lat.isFinite() && it.lon.isFinite() }
    if (points.isEmpty()) return null
    val usable = fix?.takeIf { it.isUsable() }
    return DebugRouteSnapshot(
        points = points,
        position = usable?.let { RoutePoint(it.lat, it.lon) },
        accuracyM = usable?.accM,
    )
}

/** Dessin : cadre ajusté (position incluse, même hors route), nord en haut. */
private fun DrawScope.drawDebugRoute(snap: DebugRouteSnapshot) {
    val bounds = mapBoundsOf(snap.points + listOfNotNull(snap.position)) ?: return
    val fit = bounds.fit(size.width, size.height, 64f)
    val dots = snap.points.map { fit.project(it).toOffset() }
    for (index in 1 until dots.size) {
        drawLine(MapRoute, dots[index - 1], dots[index], strokeWidth = 5f)
    }
    for (dot in dots) drawCircle(MapRouteDot, radius = 5f, center = dot)
    dots.lastOrNull()?.let { drawCircle(MapDestination, radius = 11f, center = it) }
    val position = snap.position?.let { fit.project(it).toOffset() } ?: return
    val accuracyPx = fit.metersToPx(snap.accuracyM?.toDouble() ?: 0.0)
    if (accuracyPx > 0f) drawCircle(MapAccuracy, radius = accuracyPx, center = position)
    drawCircle(MapPosition, radius = 9f, center = position)
}

private fun MapPx.toOffset(): Offset = Offset(x, y)
