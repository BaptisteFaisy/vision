package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask
import kotlin.math.abs
import kotlin.math.floor

/**
 * Grille réduite de luminance : chaque cellule est la **moyenne** d'un carré `stride × stride`
 * du plan d'entrée — et non un point sur deux, qui laisserait le bruit du capteur décider du
 * gagnant. La réduction divise le coût de la recherche par `stride²` : c'est la « grille
 * réduite » de la mission (256×103 → 128×51 à `stride = 2`).
 *
 * [levels] est réutilisé d'une frame à l'autre par [reduceInto] tant que la taille ne change
 * pas : après la première frame, une estimation n'alloue plus rien sur le chemin chaud.
 */
internal class MotionGrid(val width: Int, val height: Int, val levels: IntArray) {

    fun at(x: Int, y: Int): Int = levels[y * width + x]
}

/** Réduit [mask] dans [reuse] si ses dimensions conviennent, sinon dans un tampon neuf. */
internal fun reduceInto(mask: GrayMask, stride: Int, reuse: MotionGrid?): MotionGrid? {
    if (stride < 1) return null
    val width = mask.width / stride
    val height = mask.height / stride
    if (width < MIN_GRID || height < MIN_GRID) return null
    val grid = if (reuse != null && reuse.width == width && reuse.height == height) {
        reuse
    } else {
        MotionGrid(width, height, IntArray(width * height))
    }
    fillBlocks(grid, mask, stride)
    return grid
}

/** Moyenne par bloc ; `levelAt` borne déjà les bords (hors plan, le niveau vaut 0). */
private fun fillBlocks(grid: MotionGrid, mask: GrayMask, stride: Int) {
    val area = stride * stride
    for (gy in 0 until grid.height) {
        for (gx in 0 until grid.width) {
            var sum = 0
            for (y in gy * stride until gy * stride + stride) {
                for (x in gx * stride until gx * stride + stride) sum += mask.levelAt(x, y)
            }
            grid.levels[gy * grid.width + gx] = sum / area
        }
    }
}

/**
 * Les quatre grilles d'une estimation : les deux plans, une fois réduits pour la recherche
 * grossière et une fois pour la recherche fine. Les tampons sont **réalloués seulement si la
 * taille du plan change** (comme les tables de `WarpPlan` de u11), donc une estimation ne
 * coûte aucune allocation une fois la première faite.
 */
internal class MotionGrids {
    var refCoarse: MotionGrid = EMPTY
        private set
    var curCoarse: MotionGrid = EMPTY
        private set
    var refFine: MotionGrid = EMPTY
        private set
    var curFine: MotionGrid = EMPTY
        private set

    /** Remplit les quatre grilles depuis les deux plans ; `false` si une taille est inexploitable. */
    fun fill(prev: GrayMask, cur: GrayMask, coarseStride: Int, fineStride: Int): Boolean {
        val rc = reduceInto(prev, coarseStride, refCoarse) ?: return false
        val cc = reduceInto(cur, coarseStride, curCoarse) ?: return false
        val rf = reduceInto(prev, fineStride, refFine) ?: return false
        val cf = reduceInto(cur, fineStride, curFine) ?: return false
        refCoarse = rc
        curCoarse = cc
        refFine = rf
        curFine = cf
        return true
    }

    private companion object {
        val EMPTY = MotionGrid(0, 0, IntArray(0))
    }
}

/** Un candidat d'alignement : translation en cellules de grille, échelle en millièmes. */
internal data class Fit(val dx: Int, val dy: Int, val scalePermille: Int, val cost: Long)

/**
 * Coût d'alignement de [ref] sur [cur] pour ([dx], [dy], [scalePermille]), en millièmes de
 * niveau par point — donc comparable d'une grille à l'autre — ou [NO_COST] si trop peu de
 * points de [ref] tombent dans [cur] (mouvement qui sort du champ).
 *
 * Même modèle que `MaskMotion` : translation + échelle **autour du centre**, échantillonnage
 * au plus proche — inutile d'interpoler, chaque cellule est déjà une moyenne de blocs.
 */
internal fun alignmentCost(
    ref: MotionGrid,
    cur: MotionGrid,
    dx: Int,
    dy: Int,
    scalePermille: Int,
): Long {
    val cx = (ref.width - 1) / 2.0
    val cy = (ref.height - 1) / 2.0
    val scale = scalePermille / 1000.0
    var sum = 0L
    var hits = 0
    for (y in 0 until ref.height) {
        val sy = floor(cy + (y - cy) * scale + dy).toInt()
        if (sy !in 0 until cur.height) continue
        val row = sy * cur.width
        for (x in 0 until ref.width) {
            val sx = floor(cx + (x - cx) * scale + dx).toInt()
            if (sx !in 0 until cur.width) continue
            sum += abs(ref.at(x, y) - cur.levels[row + sx])
            hits++
        }
    }
    return if (hits < MIN_HITS) NO_COST else sum * 1000L / hits
}

/** Côté minimal d'une grille réduite : en dessous, la SAD n'a plus de portée statistique. */
private const val MIN_GRID = 8

/** Points alignés en dessous desquels un candidat n'est plus comparable aux autres. */
private const val MIN_HITS = 64

/** Coût d'un candidat impossible : jamais retenu, jamais confondu avec un vrai coût. */
internal const val NO_COST = Long.MAX_VALUE
