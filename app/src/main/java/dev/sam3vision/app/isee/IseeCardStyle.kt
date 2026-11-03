package dev.sam3vision.app.isee

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Coins des blocs iSee : la caméra comme la carte partagent les mêmes bords (16 dp). */
internal val IseeBlockShape = RoundedCornerShape(16.dp)

/** Marges latérales des blocs iSee : la caméra comme la carte respirent de 16 dp. */
internal val IseeBlockSide: Dp = 16.dp

/** Rayon natif des blocs (dp) : les surfaces Android (caméra, lunettes, carte) le lisent. */
internal const val ISEE_BLOCK_RADIUS_DP = 16f

/**
 * Arrondit une surface Android aux coins des blocs iSee : le clip Compose ne rogne
 * pas les vues natives (dessinées après le canevas), seul le contour natif les
 * arrondit vraiment. À poser une fois à la création de la vue.
 */
internal fun View.clipToIseeBlock() {
    val radius = ISEE_BLOCK_RADIUS_DP * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}
