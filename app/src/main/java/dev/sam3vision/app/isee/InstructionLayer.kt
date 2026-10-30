package dev.sam3vision.app.isee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.drawDistanceBadgeSurface
import dev.sam3vision.core.live.GuidanceInstruction
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.GuidanceWord

/** Texte de la consigne : blanc semi-gras tabulaire, comme les pastilles de distance. */
private const val INSTRUCTION_FONT_SP = 20
private const val INSTRUCTION_PAD_H = 28f
private const val INSTRUCTION_PAD_V = 14f
private const val INSTRUCTION_CORNER = 24f
private const val INSTRUCTION_STROKE = 3f

/** La consigne finit à 74 % de la hauteur : au-dessus de la flèche (82 %). */
private const val INSTRUCTION_BOTTOM_FRACTION = 0.74f

/** Opacité du texte quand le pulse est périmé (donnée vieille). */
private const val STALE_TEXT_ALPHA = 0.45f

/**
 * Consigne du battement 500 ms sur la vidéo : « Gauche · ≈ 2,1 m ». Posée
 * après la flèche [SteeringLayer], même code couleur (vert voie libre, ambre
 * passage conseillé) ; atténuée + « … » quand le pulse est périmé. Rien sans
 * pulse (guidage coupé ou pas encore de frame).
 */
@Composable
internal fun InstructionLayer(pulse: GuidancePulse?, modifier: Modifier = Modifier) {
    if (pulse == null) return
    val line = GuidanceInstruction.text(pulse)
    val rim = if (pulse.word == GuidanceWord.Straight) STEERING_FREE else STEERING_BLOCKED
    val alpha = if (pulse.stale) STALE_TEXT_ALPHA else 1f
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val layout = measurer.measure(
            line,
            TextStyle(
                color = Color.White.copy(alpha = alpha),
                fontSize = INSTRUCTION_FONT_SP.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
        val width = layout.size.width + INSTRUCTION_PAD_H * 2f
        val height = layout.size.height + INSTRUCTION_PAD_V * 2f
        val left = (size.width - width) / 2f
        val top = size.height * INSTRUCTION_BOTTOM_FRACTION - height
        drawDistanceBadgeSurface(rim, Offset(left, top), Size(width, height), INSTRUCTION_CORNER, INSTRUCTION_STROKE)
        drawText(layout, topLeft = Offset(left + INSTRUCTION_PAD_H, top + INSTRUCTION_PAD_V))
    }
}
