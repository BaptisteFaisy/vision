package dev.sam3vision.core.distance

import kotlin.math.hypot

/**
 * Distorsion radiale mesurée d'un objectif (mire sur damier), modèle de Brown
 * simplifié : un point à distance normalisée `r` du centre optique est repoussé
 * d'un facteur `1 + k1·r² + k2·r⁴`. Barillet (`k1 < 0`, le cas des téléphones)
 * ou coussinet (`k1 > 0`) : [Distortion] redresse les deux.
 *
 * Coeffs nuls = pas de distorsion ([Distortion] rend alors le point inchangé).
 * `null` (défaut dans [LensSpec]) = pas de mesure : le sténopé travaille brut.
 */
data class RadialDistortion(
    val k1: Float,
    val k2: Float = 0f,
)

/**
 * Redressement radial des coordonnées image, avant toute mesure de distance.
 *
 * Le sténopé suppose des rayons droits ; un grand-angle les courbe (jusqu'à
 * quelques pixels aux bords), et la hauteur d'une boîte mesurée loin du centre
 * ne vaut plus la focale. [undistortX]/[undistortY] replacent chaque point sur
 * son rayon idéal : le centre optique est invariant, les bords bougent le plus.
 *
 * La normalisation est la demi-diagonale ([normPx], `r = 1` aux coins) : les
 * coeffs gardent le même sens quelle que soit la taille de l'image analysée.
 */
object Distortion {

    /**
     * Rayon de normalisation d'une image `[width]`×`[height]` : sa
     * demi-diagonale en pixels (`0f` si l'image est vide).
     */
    fun normPx(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return hypot(width / 2f, height / 2f)
    }

    /**
     * Abscisse redressée du point ([x], [y]), centre optique ([cx], [cy]).
     * Identité sans [distortion], à rayon nul, ou si une entrée n'est pas finie.
     */
    fun undistortX(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        normPx: Float,
        distortion: RadialDistortion?,
    ): Float {
        if (distortion == null || normPx <= 0f) return x
        if (distortion.k1 == 0f && distortion.k2 == 0f) return x
        if (!x.isFinite() || !y.isFinite()) return x
        return cx + (x - cx) * factor(x, y, cx, cy, normPx, distortion)
    }

    /**
     * Ordonnée redressée du point ([x], [y]), centre optique ([cx], [cy]).
     * Identité sans [distortion], à rayon nul, ou si une entrée n'est pas finie.
     */
    fun undistortY(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        normPx: Float,
        distortion: RadialDistortion?,
    ): Float {
        if (distortion == null || normPx <= 0f) return y
        if (distortion.k1 == 0f && distortion.k2 == 0f) return y
        if (!x.isFinite() || !y.isFinite()) return y
        return cy + (y - cy) * factor(x, y, cx, cy, normPx, distortion)
    }

    /** Facteur `1 + k1·r² + k2·r⁴` au point ([x], [y]). */
    private fun factor(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        normPx: Float,
        distortion: RadialDistortion,
    ): Float {
        val dx = (x - cx) / normPx
        val dy = (y - cy) / normPx
        val r2 = dx * dx + dy * dy
        return 1f + distortion.k1 * r2 + distortion.k2 * r2 * r2
    }
}
