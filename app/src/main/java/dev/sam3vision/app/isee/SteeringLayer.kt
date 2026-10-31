package dev.sam3vision.app.isee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue

/** Chemin libre : le même vert que le feu Traversée. */
internal val STEERING_FREE = Color(0xFF2ECC71)

/** Cap bouché : le même ambre que le bandeau de scan ([CROSSING_ACCENT_OFFER]). */
internal val STEERING_BLOCKED = Color(0xFFF1C40F)

/**
 * Flèche du chemin sur la vidéo : verte sur voie libre, ambre quand le cap
 * est bouché — elle pointe alors le passage conseillé ([SteeringCue.arrowDir]).
 * Posée après les pastilles de distance, en bas de carte. Rien de composé
 * sans chemin (guidage coupé ou mode Traversée).
 */
@Composable
internal fun SteeringLayer(cue: SteeringCue?, modifier: Modifier = Modifier) {
    if (cue == null) return
    val color = if (cue.blocked) STEERING_BLOCKED else STEERING_FREE
    Canvas(modifier.fillMaxSize()) { drawSteeringArrow(cue.arrowDir(), color) }
}

/** Triangle bas-centré pointant [dir], ~1/6 de la largeur de carte. */
private fun DrawScope.drawSteeringArrow(dir: SteeringCap, color: Color) {
    val cx = size.width / 2f
    val cy = size.height * 0.82f
    val arm = (size.width / 6f).coerceAtMost(size.height / 3f)
    val path = Path()
    when (dir) {
        SteeringCap.Straight -> arrowUp(path, cx, cy, arm)
        SteeringCap.Left -> arrowLeft(path, cx, cy, arm)
        SteeringCap.Right -> arrowRight(path, cx, cy, arm)
    }
    drawPath(path, color.copy(alpha = 0.92f))
}

/** Pointe en haut : « tout droit ». */
private fun arrowUp(path: Path, cx: Float, cy: Float, arm: Float) {
    path.moveTo(cx, cy - arm)
    path.lineTo(cx - arm * 0.7f, cy + arm * 0.5f)
    path.lineTo(cx + arm * 0.7f, cy + arm * 0.5f)
    path.close()
}

/** Pointe à gauche : « par là ». */
private fun arrowLeft(path: Path, cx: Float, cy: Float, arm: Float) {
    path.moveTo(cx - arm, cy)
    path.lineTo(cx + arm * 0.5f, cy - arm * 0.7f)
    path.lineTo(cx + arm * 0.5f, cy + arm * 0.7f)
    path.close()
}

/** Pointe à droite : « par là ». */
private fun arrowRight(path: Path, cx: Float, cy: Float, arm: Float) {
    path.moveTo(cx + arm, cy)
    path.lineTo(cx - arm * 0.5f, cy - arm * 0.7f)
    path.lineTo(cx - arm * 0.5f, cy + arm * 0.7f)
    path.close()
}
