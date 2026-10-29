package dev.sam3vision.app.isee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.sam3vision.app.voicenav.RouteMapSnapshot
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.app.voicenav.rememberRouteMapSnapshot
import dev.sam3vision.core.nav.MapPx
import dev.sam3vision.core.nav.fit
import dev.sam3vision.core.nav.mapBoundsOf
import dev.sam3vision.core.net.HttpSignalsClient

private val MapRoute = Color(0xFF7FA8D9)
private val MapRouteDot = Color(0xFF5B87C7)
private val MapDestination = Color(0xFFD97F7F)
private val MapPosition = Color(0xFF2F6FED)
private val MapAccuracy = Color(0xFF2F6FED).copy(alpha = 0.12f)
private val MapSignal = Color(0xFFD92D20)

/**
 * Carte de debug en fond de l'écran iSee : tracé schématique de l'itinéraire
 * (un segment par manœuvre), pastille d'arrivée, position GPS + cercle de
 * précision, feux tricolores. Première couche de la scène (derrière la rue,
 * la boule et les textes) : elle ne gêne jamais l'évitement d'obstacles.
 *
 * Rien n'est composé quand le réglage est coupé, hors guidage, ou sans
 * itinéraire — l'écran est alors exactement comme avant.
 */
@Composable
fun DebugRouteMapLayer(debugEnabled: Boolean, viewModel: IseeViewModel, modifier: Modifier = Modifier) {
    if (!debugEnabled) return
    val navState by viewModel.voiceNavState.collectAsState()
    if (navState !is VoiceNavUiState.Guiding) return
    val signalsClient = remember { HttpSignalsClient(viewModel::routeBaseUrl) }
    val snap by rememberRouteMapSnapshot(
        snapshot = viewModel::debugNavSnapshot,
        fetchSignals = { bounds ->
            if (viewModel.routeBaseUrl().isBlank()) {
                emptyList()
            } else {
                signalsClient.signals(bounds).getOrElse { emptyList() }
            }
        },
    )
    val current = snap ?: return
    Canvas(modifier.fillMaxSize()) { drawDebugRoute(current) }
}

/** Dessin : cadre ajusté (position incluse, même hors route), nord en haut. */
private fun DrawScope.drawDebugRoute(snap: RouteMapSnapshot) {
    val bounds = mapBoundsOf(snap.points + listOfNotNull(snap.position)) ?: return
    val fit = bounds.fit(size.width, size.height, 64f)
    val dots = snap.points.map { fit.project(it).toOffset() }
    for (index in 1 until dots.size) {
        drawLine(MapRoute, dots[index - 1], dots[index], strokeWidth = 5f)
    }
    for (dot in dots) drawCircle(MapRouteDot, radius = 5f, center = dot)
    dots.lastOrNull()?.let { drawCircle(MapDestination, radius = 11f, center = it) }
    for (signal in snap.signals) {
        val center = fit.project(signal).toOffset()
        drawCircle(Color.White, radius = 7f, center = center)
        drawCircle(MapSignal, radius = 5f, center = center)
    }
    val position = snap.position?.let { fit.project(it).toOffset() } ?: return
    val accuracyPx = fit.metersToPx(snap.accuracyM?.toDouble() ?: 0.0)
    if (accuracyPx > 0f) drawCircle(MapAccuracy, radius = accuracyPx, center = position)
    drawCircle(MapPosition, radius = 9f, center = position)
}

private fun MapPx.toOffset(): Offset = Offset(x, y)
