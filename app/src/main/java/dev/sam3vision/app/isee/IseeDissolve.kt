package dev.sam3vision.app.isee

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import dev.sam3vision.core.iseedesign.CARD_COLS
import dev.sam3vision.core.iseedesign.CARD_ROWS
import dev.sam3vision.core.iseedesign.DISSOLVE_MS
import dev.sam3vision.core.iseedesign.DissolvePlan
import dev.sam3vision.core.iseedesign.SceneGeom
import dev.sam3vision.core.iseedesign.bubbleMorph
import dev.sam3vision.core.iseedesign.iSeeGeometry
import dev.sam3vision.core.iseedesign.pixelStage

/**
 * La dissolution en cours : la grille de la capture (dernier échantillon de la
 * preview caméra, ou la photo), son plan de vol, et la géométrie px du moment.
 * Vivante ~2,6 s : la carte se désagrège en damier pendant que la gerbe de pixels
 * part au-dessus d'elle et plonge dans la bulle qui grandit.
 */
internal class DissolveRun internal constructor(
    val grid: PixelGrid,
    val plan: DissolvePlan,
    val geom: SceneGeom,
)

/** Le monde posé sur la bulle : il survit à la dissolution jusqu'à la capture suivante. */
internal class WorldMemory internal constructor(
    val grid: PixelGrid,
    val plan: DissolvePlan,
    /** Id du message auquel le monde s'accroche (la réponse qui arrivera au fil). */
    var boundId: Long? = null,
)

/** Overlay plein écran : les cellules restantes de la carte, puis les pixels en vol. */
@Composable
internal fun DissolveOverlay(
    run: DissolveRun,
    t: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val time = t.value
        val colors = run.grid.colors
        if (colors.isEmpty()) return@Canvas
        val cnt = minOf(colors.size, run.plan.size())
        val cellW = run.geom.cardW / CARD_COLS
        val cellH = run.geom.cardH / CARD_ROWS
        // La carte qui se désagrège : seule une cellule pas encore détachée est dessinée.
        for (i in 0 until cnt) {
            val path = run.plan.paths[i]
            if (time >= path.detach) continue
            drawRect(
                color = Color(colors[i]),
                topLeft = Offset(path.cellX - cellW * 0.46f, path.cellY - cellH * 0.46f),
                size = Size(cellW * 0.92f, cellH * 0.92f),
            )
        }
        // La gerbe en vol : du bord de la carte jusqu'à la tache du globe.
        for (i in 0 until cnt) {
            val stage = pixelStage(run.plan.paths[i], run.geom, time) ?: continue
            val s = cellW * stage.size.coerceIn(0.2f, 1.2f)
            drawRect(
                color = Color(colors[i]),
                topLeft = Offset(stage.x - s / 2f, stage.y - s / 2f),
                size = Size(s, s * 0.92f),
                alpha = stage.alpha,
            )
        }
    }
}

/**
 * La carte gelée pendant la dissolution : cadre blanc, aucun contenu live —
 * l'overlay dessine par-dessus les cellules qui restent et le vol des pixels.
 */
@Composable
internal fun BoxWithConstraintsScope.FrozenCard(run: DissolveRun, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val g = run.geom
    Box(
        modifier
            .align(Alignment.TopCenter)
            .offset(y = with(density) { (g.cardCy - g.cardH / 2f).toDp() })
            .size(with(density) { g.cardW.toDp() }, with(density) { g.cardH.toDp() })
            .clip(RoundedCornerShape(26.dp))
            .border(3.dp, IseeSceneColors.cameraFrame, RoundedCornerShape(26.dp))
            .background(Color(0xFFF7F8FC)),
    )
}
