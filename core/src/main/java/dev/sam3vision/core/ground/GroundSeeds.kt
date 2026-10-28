package dev.sam3vision.core.ground

import kotlin.math.sqrt

/**
 * Graines de sol pour le RANSAC : méthode `gradient` (floue Gauss 5×5 + Sobel/8,
 * `gradient_analysis.py`) et repli `lower_half` (`pipeline.py:76-80`).
 * La méthode `dl` (U-Net NYU, indoor-only, poids absents) est exclue du portage.
 */
object GroundSeeds {

    /**
     * Graines gradient : pixels valides au gradient lisse, dans les deux tiers
     * bas de l'image. Un drop-off franc (marche) a un fort gradient vertical
     * positif et n'ensemence donc pas le sol — c'est voulu.
     */
    fun fromGradient(
        depthMeters: FloatArray,
        width: Int,
        height: Int,
        valid: BooleanArray,
        config: GroundConfig = GroundConfig(),
    ): BooleanArray {
        val seeds = BooleanArray(width * height)
        if (depthMeters.size != seeds.size || valid.size != seeds.size) return seeds
        val smooth = blur5(depthMeters, width, height)
        val gradMag = sobelOver8(smooth, width, height).second
        for (y in height / 3 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (valid[i] && gradMag[i] < config.gradObstacleThreshold) seeds[i] = true
            }
        }
        return seeds
    }

    /** Repli sans calcul : moitié basse ∩ valide (`evaluate.py:241-246`). */
    fun lowerHalf(width: Int, height: Int, valid: BooleanArray): BooleanArray {
        val seeds = BooleanArray(width * height)
        if (valid.size != seeds.size) return seeds
        for (y in height / 2 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (valid[i]) seeds[i] = true
            }
        }
        return seeds
    }

    /**
     * [primary] si elle tient au moins [GroundConfig.minSeeds] graines, sinon
     * la moitié basse (`pipeline.py:76-80`, seuil commun aux méthodes).
     */
    fun withFallback(
        primary: BooleanArray,
        width: Int,
        height: Int,
        valid: BooleanArray,
        config: GroundConfig = GroundConfig(),
    ): BooleanArray {
        if (primary.size != width * height) return lowerHalf(width, height, valid)
        if (countTrue(primary) >= config.minSeeds) return primary
        return lowerHalf(width, height, valid)
    }

    /** Flou gaussien 5×5 séparable [1,4,6,4,1]/16, bords dupliqués. */
    private fun blur5(src: FloatArray, width: Int, height: Int): FloatArray {
        val kernel = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val tmp = FloatArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in -2..2) {
                    acc += kernel[k + 2] * src[y * width + (x + k).coerceIn(0, width - 1)]
                }
                tmp[y * width + x] = acc / 16f
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in -2..2) {
                    acc += kernel[k + 2] * tmp[(y + k).coerceIn(0, height - 1) * width + x]
                }
                out[y * width + x] = acc / 16f
            }
        }
        return out
    }

    /**
     * Gradients de Sobel /8 : `gradV` (positif quand la profondeur augmente
     * vers le bas) et magnitude. Bords dupliqués, comme le flou.
     */
    private fun sobelOver8(src: FloatArray, width: Int, height: Int): Pair<FloatArray, FloatArray> {
        val gradV = FloatArray(src.size)
        val gradMag = FloatArray(src.size)
        for (y in 0 until height) {
            val up = (y - 1).coerceIn(0, height - 1)
            val down = (y + 1).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val left = (x - 1).coerceIn(0, width - 1)
                val right = (x + 1).coerceIn(0, width - 1)
                val gx = sobelResponse(src, width, left, x, right, up, y, down, true)
                val gy = sobelResponse(src, width, left, x, right, up, y, down, false)
                val i = y * width + x
                gradV[i] = gy
                gradMag[i] = sqrt(gx * gx + gy * gy)
            }
        }
        return gradV to gradMag
    }

    /** Une réponse de Sobel /8, horizontale ou verticale. */
    private fun sobelResponse(
        src: FloatArray,
        width: Int,
        left: Int,
        x: Int,
        right: Int,
        up: Int,
        y: Int,
        down: Int,
        horizontal: Boolean,
    ): Float {
        val a = src[up * width + left]
        val b = src[up * width + x]
        val c = src[up * width + right]
        val d = src[y * width + left]
        val f = src[y * width + right]
        val g = src[down * width + left]
        val h = src[down * width + x]
        val k = src[down * width + right]
        if (horizontal) return ((c + 2f * f + k) - (a + 2f * d + g)) / 8f
        return ((g + 2f * h + k) - (a + 2f * b + c)) / 8f
    }

}
