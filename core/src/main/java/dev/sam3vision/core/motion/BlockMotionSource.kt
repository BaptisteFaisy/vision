package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask

/**
 * Estimateur de mouvement de la lane L13 : **bloc-matching pur JVM** (SAD — pas de gradient,
 * pas d'OpenCV) sur une **grille réduite**, translation + échelle.
 *
 * Deux niveaux, pour que le coût tienne dans le budget de propagation locale (10 ms, u09) :
 * 1. **translation grossière** — grille à [coarseStride] (4), échelle neutre, ±
 *    [coarseSearchCells] cellules autour de zéro : 81 candidats sur 1 664 points ;
 * 2. **translation + échelle** — grille à [fineStride] (2), autour du gagnant grossier :
 *    [scalesPermille] échelles × 9 translations sur 6 528 points.
 *
 * Le résultat est rendu en **pixels du plan de luminance** : la grille fine a un pas de
 * [fineStride] px, donc le mouvement est connu à 2 px de plan près (1 px de masque).
 *
 * Aucun chiffre inventé : les seuils sont repris de l'existant de ce dépôt.
 * - l'amplitude de recherche suit `DEFAULT_SEARCH_PX = 6` à 512 px de large
 *   (`core/predict/BlockShift.kt`), soit ~1 % de l'image par frame ; la grille grossière
 *   couvre ±16 px du plan 256, de cet ordre ;
 * - `TRUST_MIN = 0,12f` est le seuil de `Shift.TRUST_MIN` : les deux estimateurs rejettent
 *   donc les mêmes scènes plates ;
 * - l'échelle est testée par paliers de 2 % (±4 %) : 1 % de zoom sur 256 px fait 2,5 px de
 *   masque, sous la demi-cellule que la grille distingue.
 *
 * Limite assumée : modèle rigide translation + échelle, sans rotation ni déformation non
 * rigide, sans occlusion. C'est au scheduler (u12) de redemander un keyframe quand le
 * masque propagé se dégrade trop.
 *
 * Non sûr entre threads (les grilles sont réutilisées d'une frame à l'autre, comme les tables
 * de `WarpPlan`) : une instance par thread — le calque local en a une.
 */
class BlockMotionSource(
    private val coarseStride: Int = COARSE_STRIDE,
    private val fineStride: Int = FINE_STRIDE,
    private val coarseSearchCells: Int = COARSE_SEARCH_CELLS,
    private val scalesPermille: IntArray = DEFAULT_SCALES.copyOf(),
    private val trustMin: Float = TRUST_MIN,
) : MotionSource {

    private val grids = MotionGrids()

    /** État des deux recherches, réutilisé : une estimation ne doit rien allouer. */
    private val coarse = Fit()
    private val fine = Fit()

    init {
        require(coarseStride > 0 && fineStride > 0) { "invalid grid stride" }
        require(coarseStride % fineStride == 0) { "the coarse grid must be a multiple of the fine one" }
    }

    override fun estimateSince(prevLuma: GrayMask, curLuma: GrayMask): MotionSample? {
        if (prevLuma.width != curLuma.width || prevLuma.height != curLuma.height) return null
        if (!grids.fill(prevLuma, curLuma, coarseStride, fineStride)) return null
        val staticCost = alignmentCost(grids.refFine, grids.curFine, 0, 0, NEUTRAL_SCALE)
        if (staticCost == NO_COST) return null
        if (staticCost == 0L) return MotionSample.Zero
        if (!coarseFit()) return null
        if (!fineFit()) return null
        val gain = confidence(staticCost, fine.cost)
        if (gain < trustMin) return null
        return MotionSample(
            dx = fine.dx * fineStride.toFloat(),
            dy = fine.dy * fineStride.toFloat(),
            scale = fine.scalePermille / 1000f,
            confidence = gain,
        )
    }

    /**
     * Niveau grossier : translation seule, échelle neutre, sur la grille grossière.
     * `false` si aucun candidat n'a assez de points alignés.
     */
    private fun coarseFit(): Boolean {
        coarse.reset()
        coarse.translation(grids.refCoarse, grids.curCoarse, 0, 0, coarseSearchCells, NEUTRAL_SCALE)
        return coarse.cost != NO_COST
    }

    /**
     * Niveau fin : translation ±1 cellule de la grille fine, à chaque échelle essayée.
     * `false` si aucun candidat n'a assez de points alignés.
     */
    private fun fineFit(): Boolean {
        val ratio = coarseStride / fineStride
        fine.reset()
        fine.withScales(
            grids.refFine,
            grids.curFine,
            coarse.dx * ratio,
            coarse.dy * ratio,
            1,
            scalesPermille,
        )
        return fine.cost != NO_COST
    }

    companion object {
        /** Pas de la grille grossière : 4 → 64×26 depuis le plan 256×103. */
        const val COARSE_STRIDE = 4

        /** Pas de la grille fine : 2 → 128×51 depuis le plan 256×103. */
        const val FINE_STRIDE = 2

        /** Amplitude de la recherche grossière, en cellules de grille grossière (±16 px de plan). */
        const val COARSE_SEARCH_CELLS = 4

        /** Paliers d'échelle essayés : ±4 % par pas de 2 %. */
        val DEFAULT_SCALES = intArrayOf(960, 980, 1000, 1020, 1040)

        /** Seuil de confiance, repris de `Shift.TRUST_MIN` (`core/predict/BlockShift.kt`). */
        const val TRUST_MIN = 0.12f

        /** Échelle neutre : « rien n'a changé ». */
        internal const val NEUTRAL_SCALE = 1000
    }
}

/** Gain de l'alignement retenu sur l'immobile, dans [0, 1] — même formule que `Shift`. */
private fun confidence(zeroCost: Long, bestCost: Long): Float {
    if (zeroCost <= 0L || zeroCost == NO_COST) return 0f
    return ((zeroCost - bestCost).toFloat() / zeroCost).coerceIn(0f, 1f)
}
