package dev.sam3vision.core.depth

/**
 * Carte de profondeur monoculaire : une valeur flottante par pixel, en
 * **proximité** (grande valeur = proche, comme la sortie brute de MiDaS et de
 * Depth Anything V2 — une disparité, pas des mètres). Les deux modèles retenus
 * sont relatifs : l'échelle métrique vient d'une ancre sténopé
 * ([DepthFusion.calibrateScale]), jamais de la carte seule.
 * Pur Kotlin (miroir de `GrayMask`), testable sur JVM.
 */
data class DepthMap(val width: Int, val height: Int, val values: FloatArray) {

    val size: Int get() = width * height

    /** Proximité en ([x], [y]), `0f` hors image (même convention que `GrayMask`). */
    fun at(x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        return values[y * width + x]
    }

    /** Carte ramenée dans [0, 1] (min→0, max→1) ; carte de zéros si l'étendue est nulle. */
    fun normalized01(): DepthMap {
        if (values.isEmpty()) return this
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        if (!min.isFinite() || max - min <= 0f) return DepthMap(width, height, FloatArray(size))
        val span = max - min
        return DepthMap(width, height, FloatArray(size) { i -> (values[i] - min) / span })
    }

    /**
     * Rééchantillonnage bilinéaire vers ([targetWidth], [targetHeight]) : aligne la
     * carte du modèle (ex. 252×252) sur les masques SAM (ex. 1024×768) avant fusion.
     */
    fun resampledTo(targetWidth: Int, targetHeight: Int): DepthMap {
        require(targetWidth > 0 && targetHeight > 0) { "cible vide : $targetWidth×$targetHeight" }
        if (targetWidth == width && targetHeight == height) return this
        val out = FloatArray(targetWidth * targetHeight)
        val scaleX = axisScale(width, targetWidth)
        val scaleY = axisScale(height, targetHeight)
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                out[y * targetWidth + x] = bilinear(x * scaleX, y * scaleY)
            }
        }
        return DepthMap(targetWidth, targetHeight, out)
    }

    /**
     * Pas source par pixel cible sur un axe : `(taille - 1) / (cible - 1)`,
     * `0f` si la cible n'a qu'un pixel (tout s'échantillonne à l'origine).
     */
    private fun axisScale(size: Int, target: Int): Float {
        if (target <= 1) return 0f
        return (size - 1f) / (target - 1f)
    }

    private fun bilinear(fx: Float, fy: Float): Float {
        val x0 = fx.toInt().coerceIn(0, width - 1)
        val y0 = fy.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceIn(0, width - 1)
        val y1 = (y0 + 1).coerceIn(0, height - 1)
        val tx = (fx - fx.toInt()).coerceIn(0f, 1f)
        val ty = (fy - fy.toInt()).coerceIn(0f, 1f)
        val top = values[y0 * width + x0] * (1f - tx) + values[y0 * width + x1] * tx
        val bottom = values[y1 * width + x0] * (1f - tx) + values[y1 * width + x1] * tx
        return top * (1f - ty) + bottom * ty
    }
}
