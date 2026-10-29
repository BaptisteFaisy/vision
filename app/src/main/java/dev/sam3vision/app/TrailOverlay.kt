package dev.sam3vision.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.live.ObjectTrail
import dev.sam3vision.app.live.TrailLayer

/**
 * Chemins des objets suivis, posés sur l'aperçu : pour chaque piste, la
 * trajectoire de son centre (un trait dans la couleur du masque) et un point
 * plein sur sa position exacte — le « chemin au milieu » de chaque objet.
 *
 * L'overlay ne décide de rien : [dev.sam3vision.app.live.CenterTrails] lui donne
 * les chemins prêts à tracer, dans le repère de l'image analysée. Couche vide
 * (aucune piste) : **rien n'est dessiné**, le direct reste celui d'avant.
 */
@Composable
fun LiveTrailOverlay(layer: TrailLayer?, modifier: Modifier = Modifier) {
    if (layer == null || layer.empty) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val transform = cropTransform(
            layer.width,
            layer.height,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
        )
        Canvas(Modifier.fillMaxSize()) {
            layer.trails.forEach { drawTrail(it, layer.width, layer.height, transform) }
        }
    }
}

/** Un chemin : le trait des centres passés, puis le point de la position exacte. */
private fun DrawScope.drawTrail(trail: ObjectTrail, width: Int, height: Int, transform: CropTransform) {
    if (trail.points.isEmpty()) return
    val color = maskColorAt(trail.colorIndex)
    val pts = trail.points.map { Offset(transform.screenX(it.x * width), transform.screenY(it.y * height)) }
    if (pts.size > 1) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (index in 1 until pts.size) lineTo(pts[index].x, pts[index].y)
        }
        drawPath(path, color.copy(alpha = TRAIL_ALPHA), style = Stroke(width = TRAIL_STROKE.toPx()))
    }
    val head = pts.last()
    drawCircle(Color.White, radius = (TRAIL_DOT + TRAIL_RIM).toPx(), center = head)
    drawCircle(color, radius = TRAIL_DOT.toPx(), center = head)
}

/** Le trait reste sous le point : le présent prime sur le passé. */
private const val TRAIL_ALPHA = 0.9f

private val TRAIL_STROKE = 2.dp

/** Point central : pastille couleur cerclée de blanc, lisible sur tout masque. */
private val TRAIL_DOT = 4.dp
private val TRAIL_RIM = 1.5.dp
