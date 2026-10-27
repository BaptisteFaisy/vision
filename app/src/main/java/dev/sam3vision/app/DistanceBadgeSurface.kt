package dev.sam3vision.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Le fond laisse traverser la caméra ; le contraste du texte reste porté par une encre sombre. */
private val BADGE_GLASS_TOP = Color(0x7A0B1220)
private val BADGE_GLASS_BOTTOM = Color(0xA30B1220)
private const val BADGE_TINT_ALPHA = 0.10f
private const val BADGE_RIM_ALPHA = 0.92f
private const val BADGE_HIGHLIGHT_ALPHA = 0.32f

/** Verre translucide, teinte légère et contour de la couleur exacte du masque SAM. */
internal fun DrawScope.drawDistanceBadgeSurface(
    maskColor: Color,
    topLeft: Offset,
    size: Size,
    corner: Float,
    stroke: Float,
) {
    val radius = CornerRadius(corner)
    val bottom = topLeft.y + size.height
    drawRoundRect(
        Brush.verticalGradient(listOf(BADGE_GLASS_TOP, BADGE_GLASS_BOTTOM), topLeft.y, bottom),
        topLeft, size, radius,
    )
    drawRoundRect(maskColor.copy(alpha = BADGE_TINT_ALPHA), topLeft, size, radius)
    drawRoundRect(
        maskColor.copy(alpha = BADGE_RIM_ALPHA), topLeft, size, radius,
        style = Stroke(stroke),
    )
    val inset = Offset(stroke, stroke)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = BADGE_HIGHLIGHT_ALPHA), Color.Transparent),
            topLeft.y, topLeft.y + size.height * 0.6f,
        ),
        topLeft = topLeft + inset,
        size = Size((size.width - stroke * 2f).coerceAtLeast(0f), (size.height - stroke * 2f).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((corner - stroke).coerceAtLeast(0f)),
        style = Stroke(stroke * 0.5f),
    )
}
