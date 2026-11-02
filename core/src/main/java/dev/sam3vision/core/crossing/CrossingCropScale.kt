package dev.sam3vision.core.crossing

import kotlin.math.roundToInt

/**
 * Dimensions cibles du crop de feu piéton : la boîte SAM est ramenée à
 * [MAX_EDGE] px **sur son plus grand côté**, l'autre suivant le même facteur.
 *
 * POURQUOI (mesure A4, 9 photos réelles) : l'ancien calcul plafonnait chaque
 * axe séparément (`min(96, w)`, `min(96, h)`), donc une boîte 200×100 devenait
 * 96×96 — rapport d'aspect cassé, lampe étirée et remplissage du cadre gonflé.
 * [PedLightGate] décide justement sur l'élongation et le remplissage : avec ce
 * bug, 1 seul vert sur 7 était trouvé (plus 1 faux vert) ; rapport conservé,
 * 5 sur 7. Le facteur unique garde la forme de la lampe, donc les seuils de la
 * porte restent valables.
 *
 * Pur Kotlin (aucun import Android) : testé en JVM, `CrossingCropScaleTest`.
 */
object CrossingCropScale {

    /**
     * Plus grand côté du crop classifié (96 px depuis le 31/10 : la lampe y
     * garde sa forme, voir `CrossingPixels.MAX_EDGE`).
     */
    const val MAX_EDGE = 96

    /**
     * Taille du crop pour une boîte `boxWidth × boxHeight` (px bitmap) :
     * plus grand côté ramené à [MAX_EDGE], autre côté au prorata (arrondi),
     * chaque dimension bornée à au moins 1 px — une boîte 1×400 reste 1×96,
     * jamais 0. Boîte dont **les deux** côtés tiennent dans [MAX_EDGE] :
     * renvoyée telle quelle (aucun rééchantillonnage). Boîte vide ou
     * négative (jamais produite par l'appelant) : `0 to 0`.
     */
    fun scaledSize(boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
        if (boxWidth <= 0 || boxHeight <= 0) return 0 to 0
        val longest = maxOf(boxWidth, boxHeight)
        if (longest <= MAX_EDGE) return boxWidth to boxHeight
        val factor = MAX_EDGE.toDouble() / longest
        return scale(boxWidth, factor) to scale(boxHeight, factor)
    }

    /** Un côté mis à l'échelle, arrondi et borné à `1..MAX_EDGE` (jamais 0). */
    private fun scale(side: Int, factor: Double): Int =
        (side * factor).roundToInt().coerceIn(1, MAX_EDGE)
}
