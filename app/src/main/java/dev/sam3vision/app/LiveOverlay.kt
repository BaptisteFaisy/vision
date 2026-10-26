package dev.sam3vision.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.live.PredictedBox
import dev.sam3vision.app.live.PredictionLayer

/**
 * Overlay du direct : les **boîtes prédites** (T8) de la frame caméra, en plus des
 * masques serveur.
 *
 * L'overlay ne décide de rien : [dev.sam3vision.app.live.LivePrediction] ne lui donne
 * que les boîtes dont le masque a vieilli — exactement celles que la prédiction prend
 * en charge. Le rectangle est **fin et vide**, donc le masque reste lisible dessous,
 * et son opacité suit la confiance : une prédiction en fin de vie s'efface au lieu de
 * mentir.
 *
 * Couche vide (flag éteint, aucun objet suivi, rien de périmé) : **rien n'est dessiné**,
 * le direct reste celui d'avant. Les **pastilles de distance ne bougent jamais** : une
 * distance vient de la géométrie de l'image, pas d'une extrapolation.
 */
@Composable
fun LivePredictionOverlay(layer: PredictionLayer?, modifier: Modifier = Modifier) {
    if (layer == null || layer.empty) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val transform = cropTransform(
            layer.width,
            layer.height,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
        )
        Canvas(Modifier.fillMaxSize()) {
            layer.boxes.forEach { drawBox(it, transform) }
        }
    }
}

/** Un rectangle prédit : fin, vide, dans la couleur du masque de son objet. */
private fun DrawScope.drawBox(predicted: PredictedBox, transform: CropTransform) {
    drawRoundRect(
        color = maskColorAt(predicted.colorIndex).copy(alpha = alphaFor(predicted.confidence)),
        topLeft = Offset(transform.screenX(predicted.box.x1), transform.screenY(predicted.box.y1)),
        size = Size(
            (predicted.box.width * transform.scale).coerceAtLeast(MIN_BOX_PX),
            (predicted.box.height * transform.scale).coerceAtLeast(MIN_BOX_PX),
        ),
        cornerRadius = CornerRadius(BOX_CORNER.toPx()),
        style = Stroke(width = BOX_STROKE.toPx()),
    )
}

/** Opacité d'une prédiction : pleine quand elle est fraîche, minimale en fin de vie. */
private fun alphaFor(confidence: Float): Float =
    MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * confidence.coerceIn(0f, 1f)

private const val MIN_ALPHA = 0.3f
private const val MAX_ALPHA = 0.85f

/** Sous deux pixels, le rectangle n'est plus lisible : il reste au moins visible. */
private const val MIN_BOX_PX = 2f

private val BOX_STROKE = 1.5.dp
private val BOX_CORNER = 6.dp