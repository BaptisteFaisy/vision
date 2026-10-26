package dev.sam3vision.core.motion

/**
 * Mouvement global entre deux luminances : translation + échelle autour du centre.
 *
 * - `dx`/`dy` : en **pixels de la grille d'estimation** (le plan de luminance du direct,
 *   256×103). L'estimateur travaille sur une grille réduite et rend donc des multiples de
 *   son pas (`stride`), c'est-à-dire au plus 1 px de masque près ;
 * - `scale` : sans dimension, 1 = pas de zoom ;
 * - `confidence` : gain du meilleur alignement sur l'immobile, dans [0, 1] — même définition
 *   que `Shift` (`core/predict/BlockShift.kt`), donc les deux seuils se comparent.
 *
 * [scaledBy] est la **conversion d'unités** de la couture L13 (`docs/L13-FUSION.md` §3) : le
 * masque vit sur une grille deux fois plus fine que la luminance (512×206 contre 256×103),
 * `sample.scaledBy(2f)` donne donc le mouvement en pixels de masque. L'échelle, sans
 * dimension, ne bouge pas.
 */
data class MotionSample(
    val dx: Float,
    val dy: Float,
    val scale: Float,
    val confidence: Float,
) {

    /** Le même mouvement sur une grille [step] fois plus fine (`step` > 1) ou plus grossière. */
    fun scaledBy(step: Float): MotionSample = copy(dx = dx * step, dy = dy * step)

    companion object {
        /** Aucun mouvement : le calque garde le masque du keyframe tel quel. */
        val Zero = MotionSample(0f, 0f, 1f, 0f)
    }
}
