package dev.sam3vision.app.voicenav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapBounds
import dev.sam3vision.core.nav.MapPx
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.fit
import dev.sam3vision.core.nav.mapBoundsOf

private val MiniRoute = Color(0xFF9DBCFA)
private val MiniDestination = Color(0xFF3ED598)
private val MiniPosition = Color.White
private val MiniSignal = Color(0xFFF2555A)

/**
 * Petite carte de l'écran de guidage : où le guidage m'emmène — tracé,
 * arrivée (vert), position (blanc) et feux tricolores (rouge), nord en haut.
 * Rien n'est composé sans tracé (repos) ; sans feux (repli, échec réseau),
 * la carte reste affichée avec « 0 feu ».
 */
@Composable
fun MiniRouteMap(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    fetchSignals: suspend (MapBounds) -> List<RoutePoint>,
    modifier: Modifier = Modifier,
) {
    val snap by rememberRouteMapSnapshot(snapshot, fetchSignals)
    val current = snap ?: return
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.surfaceHigh),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawMiniRoute(current) }
        val count = current.signals.size
        Text(
            if (count <= 1) "$count feu" else "$count feux",
            color = AppColors.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
        )
    }
}

/** Dessin : cadre ajusté (position incluse, même hors route), nord en haut. */
private fun DrawScope.drawMiniRoute(snap: RouteMapSnapshot) {
    val bounds = mapBoundsOf(snap.points + listOfNotNull(snap.position)) ?: return
    val fit = bounds.fit(size.width, size.height, 36f)
    val dots = snap.points.map { fit.project(it).toOffset() }
    for (index in 1 until dots.size) {
        drawLine(MiniRoute, dots[index - 1], dots[index], strokeWidth = 5f)
    }
    dots.lastOrNull()?.let {
        drawCircle(Color.White, radius = 11f, center = it)
        drawCircle(MiniDestination, radius = 8f, center = it)
    }
    for (signal in snap.signals) {
        val center = fit.project(signal).toOffset()
        drawCircle(Color.White, radius = 7f, center = center)
        drawCircle(MiniSignal, radius = 5f, center = center)
    }
    snap.position?.let { position ->
        val center = fit.project(position).toOffset()
        val accuracyPx = fit.metersToPx(snap.accuracyM?.toDouble() ?: 0.0)
        if (accuracyPx > 0f) drawCircle(MiniRoute.copy(alpha = 0.25f), radius = accuracyPx, center = center)
        drawCircle(MiniPosition, radius = 8f, center = center)
    }
}

private fun MapPx.toOffset(): Offset = Offset(x, y)
