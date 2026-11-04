package dev.sam3vision.app.isee

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.guidemap.GuideMapCornerRadius

/** Coins des blocs iSee : la caméra reprend ceux de la carte ([GuideMapCornerRadius], source unique). */
internal val IseeBlockShape = RoundedCornerShape(GuideMapCornerRadius)

/** Marges latérales des blocs iSee : la caméra comme la carte respirent de 16 dp. */
internal val IseeBlockSide: Dp = 16.dp

/** Marge du bloc caméra sous le bandeau système (ou sous le haut de l'écran). */
internal val IseeBlockTopGap: Dp = 8.dp

/**
 * Haut du bloc caméra : sous le bandeau système ([statusBarTop]) plus la
 * marge. Sans ce décalage, l'aperçu natif (dessiné après le canevas Compose)
 * recouvrirait le bandeau blanc et ses icônes (batterie, notifications).
 */
internal fun cameraBlockTop(statusBarTop: Dp): Dp = statusBarTop + IseeBlockTopGap

/**
 * Hauteur du bloc caméra : le décalage du haut est absorbé ici, le bas du
 * bloc ne bouge pas (boule, phrase et carte gardent leurs places).
 */
internal fun cameraBlockHeight(height: Dp, statusBarTop: Dp): Dp = height - statusBarTop

/**
 * Arrondit une surface Android aux coins des blocs iSee : le clip Compose ne rogne
 * pas les vues natives (dessinées après le canevas), seul le contour natif les
 * arrondit vraiment — au même rayon que la carte. À poser une fois à la création.
 */
internal fun View.clipToIseeBlock() {
    val radius = GuideMapCornerRadius.value * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}
