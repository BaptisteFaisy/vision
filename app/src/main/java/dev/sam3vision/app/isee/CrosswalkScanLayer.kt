package dev.sam3vision.app.isee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Le scan traversée **sur la vidéo**, sous le bandeau verdict : la consigne
 * ou le constat en cours puis les constats déjà rendus (jusqu'à 3 lignes),
 * dans la même pastille que le verdict (même police, mêmes marges, même
 * géométrie [verdictRect] — un seul design de bandeau). Rouge dès qu'un côté
 * constate un danger, vert quand les deux sont libres, ambre pendant.
 * Rien quand le scan est inactif (`null`) ou muet : l'écran reste celui
 * d'avant.
 */
@Composable
internal fun CrosswalkScanBanner(
    lines: CrosswalkScanLines?,
    modifier: Modifier = Modifier,
) {
    if (lines == null) return
    val tag = scanBannerLines(lines).joinToString("\n")
    if (tag.isEmpty()) return
    val accent = Color(lines.accent)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val laid = rememberTextMeasurer().measure(
            tag,
            TextStyle(color = Color.White, fontSize = VERDICT_FONT_SP.sp, fontWeight = FontWeight.Bold),
            maxLines = SCAN_BANNER_MAX_LINES,
        )
        val plate = verdictRect(
            laid.size.width.toFloat() + VERDICT_PAD_H_PX * 2,
            laid.size.height.toFloat() + VERDICT_PAD_V_PX * 2,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
            with(LocalDensity.current) { SCAN_TOP_MARGIN_DP.dp.toPx() },
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = accent.copy(alpha = 0.92f),
                topLeft = Offset(plate.left, plate.top),
                size = Size(plate.width, plate.height),
                cornerRadius = CornerRadius(TAG_CORNER_DP, TAG_CORNER_DP),
            )
            drawText(laid, topLeft = Offset(plate.left + VERDICT_PAD_H_PX, plate.top + VERDICT_PAD_V_PX))
        }
    }
}

/** Lignes au plus du bandeau scan : la consigne en cours + les deux constats. */
private const val SCAN_BANNER_MAX_LINES = 3

/**
 * Décalage du bandeau scan : sous le verdict (64 dp + ~17 dp de pastille une
 * ligne + respiration). [verdictRect] le ramène dans la carte si elle est
 * trop petite.
 */
private const val SCAN_TOP_MARGIN_DP = 104f
