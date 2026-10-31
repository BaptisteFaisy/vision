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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.traverse.TraversePhase

/**
 * Le feu piéton **sur la vidéo**, au même endroit que les masques SAM : un cadre
 * de la couleur de l'état et une étiquette courte (`VERT`, `ROUGE`)
 * posée au-dessus.
 *
 * Pourquoi un cadre et pas un rectangle plein : la règle des distances vaut ici
 * aussi — l'image doit rester lisible, la couleur ne sert qu'à désigner l'objet.
 * Le calcul du placement est dans [lightMarker] / [tagRect], testés en JVM ;
 * cette fonction ne fait que dessiner.
 *
 * Rien de composé quand le mode est coupé ou qu'aucun feu n'est dans le champ :
 * une étiquette orpheline vaudrait mieux que rien, mais un cadre vide mentirait
 * sur la position du feu.
 */
@Composable
internal fun CrossingOverlayLayer(
    active: Boolean,
    box: List<Float>?,
    state: CrossingState?,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier = Modifier,
    /** Phase prudente publiée (`null` = pas de verdict : aucun bandeau). */
    phase: TraversePhase? = null,
) {
    TraverseVerdictBanner(active, phase)
    val tag = crossingTag(active, state)
    if (tag.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val marker = lightMarker(box, frameWidth, frameHeight, boxW, boxH) ?: return@BoxWithConstraints
        val accent = Color(crossingAccent(active, state))
        val stroke = with(LocalDensity.current) { STROKE_DP.dp.toPx() }
        val laid = rememberTextMeasurer().measure(
            tag,
            TextStyle(color = Color.White, fontSize = TAG_FONT_SP.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        val laidH = laid.size.height.toFloat()
        val laidW = laid.size.width.toFloat()
        val tagW = laidW + TAG_PAD_H_DP * 2
        val tagH = laidH + TAG_PAD_V_DP * 2
        val plate = tagRect(marker, tagW, tagH, boxW, boxH)
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                color = accent.copy(alpha = 0.95f),
                topLeft = Offset(marker.left, marker.top),
                size = Size(marker.width, marker.height),
                style = Stroke(width = stroke),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.92f),
                topLeft = Offset(plate.left, plate.top),
                size = Size(plate.width, plate.height),
                cornerRadius = CornerRadius(TAG_CORNER_DP, TAG_CORNER_DP),
            )
            drawText(laid, topLeft = Offset(plate.left + TAG_PAD_H_DP, plate.top + TAG_PAD_V_DP))
        }
    }
}

/**
 * Le verdict **sur la vidéo**, en haut et centré, sous la puce : « peut
 * traverser » (vert) ou « ne peut pas traverser » (rouge). Rien quand le mode
 * est coupé ou qu'aucune phase n'est publiée — le direct ne publiant pas de
 * phase, son overlay reste celui d'avant.
 */
@Composable
internal fun TraverseVerdictBanner(
    active: Boolean,
    phase: TraversePhase?,
    modifier: Modifier = Modifier,
) {
    if (!active || phase == null) return
    val tag = traverseTag(active, phase)
    if (tag.isEmpty()) return
    val accent = Color(traverseAccent(active, phase))
    BoxWithConstraints(modifier.fillMaxSize()) {
        val laid = rememberTextMeasurer().measure(
            tag,
            TextStyle(color = Color.White, fontSize = VERDICT_FONT_SP.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        val plate = verdictRect(
            laid.size.width.toFloat() + VERDICT_PAD_H_PX * 2,
            laid.size.height.toFloat() + VERDICT_PAD_V_PX * 2,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
            with(LocalDensity.current) { VERDICT_TOP_MARGIN_DP.dp.toPx() },
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

/** Épaisseur du cadre : lisible sur une scène claire comme sur une nuit. */
private const val STROKE_DP = 2.5f

/** Verdict plus grand que l'étiquette du feu : c'est lui qu'on lit en marchant. */
internal const val VERDICT_FONT_SP = 14f

internal const val VERDICT_PAD_H_PX = 10f
internal const val VERDICT_PAD_V_PX = 4f

/** Décalage du bandeau sous la puce (~46 dp de puce + respiration). */
private const val VERDICT_TOP_MARGIN_DP = 64f

/** Taille de l'étiquette : petite, mais lisible sur une vidéo qui bouge. */
private const val TAG_FONT_SP = 11f

private const val TAG_PAD_H_DP = 6f
private const val TAG_PAD_V_DP = 2f
internal const val TAG_CORNER_DP = 6f
