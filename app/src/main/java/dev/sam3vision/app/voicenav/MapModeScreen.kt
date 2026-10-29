package dev.sam3vision.app.voicenav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapBounds
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.fit
import dev.sam3vision.core.nav.mapBoundsAround
import dev.sam3vision.core.settings.Settings
import java.util.Locale

/** Rayon de la vue : 200 m autour de la position (assez pour lire son quartier). */
internal const val MAP_VIEW_RADIUS_M = 200.0

/** Demi-largeur du cadre de repli quand il n'y a pas encore de position. */
private const val NO_FIX_PADDING = 0.002

private val MapCrossing = Color(0xFFD92D20)
private val MapPosition = Color(0xFF2F6FED)
private val MapAccuracy = Color(0xFF2F6FED).copy(alpha = 0.18f)

/**
 * Mode navigation sans destination : la carte plein écran, la position GPS
 * et les passages piétons connus, plus le micro (même appui long que les
 * autres écrans) pour dicter « va à … » ou « quitte la carte ».
 *
 * L'écran n'invente rien : [fix] vient de [MapPositionWatcher], [signals] de
 * `/signals` (même base que l'itinéraire), et [onLeave] de la session. Sans
 * position, la carte reste vide et le texte dit pourquoi ([gpsAvailable]).
 */
@Composable
fun MapModeScreen(
    fix: NavFix?,
    signals: List<RoutePoint>,
    gpsAvailable: Boolean,
    onLeave: () -> Unit,
    hold: VoiceNavHold,
    settings: Settings,
    onHoldTranscript: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val micUi = rememberHoldMicUiState()
    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mode navigation", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.text)
        HoldMicArea(
            hold = hold,
            settings = settings,
            onTranscript = onHoldTranscript,
            hint = VoiceNavHoldText.MAP_NOT_UNDERSTOOD,
            ui = micUi,
            modifier = Modifier.weight(1f),
        ) {
            MapCanvas(fix = fix, signals = signals, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
        }
        Text(
            positionLine(fix, gpsAvailable),
            fontSize = 20.sp,
            color = AppColors.text,
        )
        Text(
            "Maintiens la carte et dis « va à … » pour un itinéraire, ou « quitte la carte ».",
            fontSize = 15.sp,
            color = AppColors.textMuted,
        )
        Button(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger),
        ) {
            Text("Quitter", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Le cadre de projection : 200 m autour du point, ou un cadre dégénéré sans fix. */
internal fun viewBoundsOf(fix: NavFix?): MapBounds =
    if (fix == null) {
        MapBounds(NO_FIX_PADDING, NO_FIX_PADDING, NO_FIX_PADDING, NO_FIX_PADDING)
    } else {
        mapBoundsAround(RoutePoint(fix.lat, fix.lon), MAP_VIEW_RADIUS_M)
    }

/** Phrase de position : pourquoi la carte est vide, ou où l'on est. */
internal fun positionLine(fix: NavFix?, gpsAvailable: Boolean): String = when {
    fix != null -> "Position ${formatCoord(fix.lat)}, ${formatCoord(fix.lon)} — précision ${fix.accM.toInt()} m."
    !gpsAvailable -> "Position indisponible — autorise la localisation pour ce téléphone."
    else -> "Recherche de la position…"
}

/** Coordonnée lisible : 5 décimales, virgule décimale (voix et lecture françaises). */
private fun formatCoord(value: Double): String =
    String.format(Locale.FRANCE, "%.5f", value)

/** Fond de carte : passages piétons, cercle de précision, point de position. */
@Composable
private fun MapCanvas(fix: NavFix?, signals: List<RoutePoint>, modifier: Modifier) {
    Box(modifier.background(AppColors.surfaceHigh)) {
        Canvas(Modifier.fillMaxSize()) { drawMap(viewBoundsOf(fix), fix, signals) }
    }
}

/** Dessin pur : un canevas, un cadre, une position — aucun état. */
private fun DrawScope.drawMap(bounds: MapBounds, fix: NavFix?, signals: List<RoutePoint>) {
    val fit = bounds.fit(size.width, size.height, 48f)
    for (signal in signals) {
        val px = fit.project(signal)
        val center = Offset(px.x, px.y)
        drawCircle(Color.White, radius = 8f, center = center)
        drawCircle(MapCrossing, radius = 6f, center = center)
    }
    if (fix == null) return
    val here = fit.project(RoutePoint(fix.lat, fix.lon))
    val center = Offset(here.x, here.y)
    drawCircle(MapAccuracy, radius = fit.metersToPx(fix.accM.toDouble()).coerceAtLeast(6f), center = center)
    drawCircle(Color.White, radius = 13f, center = center)
    drawCircle(MapPosition, radius = 9f, center = center)
}
