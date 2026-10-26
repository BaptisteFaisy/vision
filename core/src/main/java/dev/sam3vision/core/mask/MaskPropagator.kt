package dev.sam3vision.core.mask

/** Filtre de rééchantillonnage du warp. */
enum class WarpFilter {
    /** Bilinéaire (défaut) : l'objet garde un bord doux, la frange vaut 127 (sous le seuil 128). */
    BILINEAR,

    /** Au plus proche : conserve les niveaux 0/255 d'un masque binaire. */
    NEAREST,
}

/** Niveau « fond » : tout échantillon hors de la grille vaut 0 (aucun bord inventé). */
private const val BACKGROUND: Byte = 0

/**
 * Warp **inverse** d'un masque : le masque d'un keyframe est ramené sur la frame courante
 * à partir du mouvement estimé ([MaskMotion]).
 *
 * [warpInto] écrit dans un tampon fourni par l'appelant : **aucune allocation par frame**
 * (le plan de tables est réutilisé tant que la grille ne grandit pas). [warp] est la
 * variante allouante, réservée au banc et aux comparaisons.
 *
 * Règles :
 * - un échantillon **hors de la grille** vaut fond (0) : aucun bord n'est inventé ;
 * - un mouvement nul au pixel recopie le masque tel quel ;
 * - un mouvement inexploitable (échelle hors bornes, NaN, ∞) est **refusé** : la fonction
 *   rend `false` et le tampon de sortie n'est pas touché.
 */
class MaskPropagator(val plan: WarpPlan = WarpPlan()) {

    /**
     * Warp de [src] vers [dst] (tampon réutilisé par l'appelant).
     * `false` si le warp est refusé (grilles incompatibles, masque vide, mouvement aberrant).
     */
    fun warpInto(
        src: GrayMask,
        dst: GrayMask,
        motion: MaskMotion,
        filter: WarpFilter = WarpFilter.BILINEAR,
    ): Boolean {
        if (!isWarpable(src, dst) || !motion.isUsable) return false
        plan.prepare(src.width, src.height)
        plan.update(motion)
        if (motion.isIdentity) {
            src.pixels.copyInto(dst.pixels)
            return true
        }
        if (filter == WarpFilter.NEAREST) warpNearest(src.pixels, dst.pixels)
        else warpBilinear(src.pixels, dst.pixels)
        return true
    }

    /** Warp allouant (comparatif du banc) : `null` si le warp est refusé. */
    fun warp(
        src: GrayMask,
        motion: MaskMotion,
        filter: WarpFilter = WarpFilter.BILINEAR,
    ): GrayMask? {
        val dst = GrayMask(src.width, src.height, ByteArray(src.size))
        return if (warpInto(src, dst, motion, filter)) dst else null
    }

    /** Les deux grilles doivent être de même taille et leurs pixels cohérents. */
    private fun isWarpable(src: GrayMask, dst: GrayMask): Boolean =
        src.width > 0 && src.height > 0 && src.pixels.size == src.size &&
            dst.width == src.width && dst.height == src.height && dst.pixels.size == src.size

    /**
     * Interpolation bilinéaire : poids entiers en 1/256, somme des poids = 65 536.
     * La division entière **tronce** : une frange d'un demi-pixel vaut 127 (donc sous le
     * seuil 128 d'un masque binaire) — c'est mesuré, pas arrondi au plus proche.
     */
    private fun warpBilinear(src: ByteArray, dst: ByteArray) {
        val w = plan.width
        val h = plan.height
        for (y in 0 until h) {
            val y0 = plan.sourceY(y)
            val fy = plan.fractionY(y)
            val rowOk = y0 in 0 until h
            val row0 = if (rowOk) y0 * w else 0
            val row1 = if (rowOk && y0 + 1 < h) row0 + w else -1
            val wy0 = 256 - fy
            var o = y * w
            for (x in 0 until w) {
                val x0 = plan.sourceX(x)
                if (!rowOk || x0 < 0 || x0 >= w) {
                    dst[o] = BACKGROUND
                    o++
                    continue
                }
                val fx = plan.fractionX(x)
                val x1 = x0 + 1
                val v00 = src[row0 + x0].toInt() and 0xFF
                val v10 = if (x1 < w) src[row0 + x1].toInt() and 0xFF else 0
                val v01 = if (row1 >= 0) src[row1 + x0].toInt() and 0xFF else 0
                val v11 = if (row1 >= 0 && x1 < w) src[row1 + x1].toInt() and 0xFF else 0
                val wx0 = 256 - fx
                val total = v00 * wy0 * wx0 + v10 * wy0 * fx + v01 * fy * wx0 + v11 * fy * fx
                dst[o] = (total / 65536).toByte()
                o++
            }
        }
    }

    /** Au plus proche : l'index source est l'entier le plus proche (fraction ≥ 128 → +1). */
    private fun warpNearest(src: ByteArray, dst: ByteArray) {
        val w = plan.width
        val h = plan.height
        for (y in 0 until h) {
            val y0 = plan.sourceY(y)
            val yi = y0 + if (plan.fractionY(y) >= 128) 1 else 0
            var o = y * w
            if (yi < 0 || yi >= h) {
                for (x in 0 until w) {
                    dst[o] = BACKGROUND
                    o++
                }
                continue
            }
            val row = yi * w
            for (x in 0 until w) {
                val x0 = plan.sourceX(x)
                val xi = x0 + if (plan.fractionX(x) >= 128) 1 else 0
                dst[o] = if (xi in 0 until w) src[row + xi] else BACKGROUND
                o++
            }
        }
    }
}
