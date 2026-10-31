package dev.sam3vision.core.depth

/**
 * Calibration affine de la profondeur monoculaire : DA2 est relatif à
 * échelle + décalage, donc `mètres = échelle / proximité + décalage`
 * (l'échelle seule de [DepthFusion.calibrateScale] laisse un biais).
 * Pur JVM.
 */
data class AffineCalibration(val scale: Float, val shift: Float)

/** Régression robuste de la calibration affine sur les ancres sténopé. */
object AffineScale {

    /** Rejet d'une ancre quand son résidu relatif dépasse 30 %. */
    const val OUTLIER_RESIDUAL = 0.3f

    /** Passes de rejet des ancres aberrantes avant l'ajustement final. */
    const val OUTLIER_PASSES = 2

    /**
     * Calibre sur les ancres (mètres sténopé × proximité médiane) : moindres
     * carrés sur (1/proximité → mètres) dès 3 ancres, avec rejet des
     * aberrantes (résidu > 30 %, recalcule, 2 passes max). À 1-2 ancres :
     * repli échelle médiane + décalage nul (comportement historique).
     * `null` sans ancre exploitable.
     */
    fun calibrate(anchors: List<DepthAnchor>): AffineCalibration? {
        val valid = anchors.filter { it.meters.isFinite() && it.meters > 0f }
            .filter { it.closeness.isFinite() && it.closeness > 0f }
        if (valid.isEmpty()) return null
        if (valid.size < 3) return medianFallback(valid)
        var kept = valid
        for (pass in 0 until OUTLIER_PASSES) {
            val fit = leastSquares(kept) ?: return medianFallback(valid)
            val survivors = kept.filter { residualOk(it, fit) }
            if (survivors.size == kept.size) return fit
            if (survivors.size < 3) return medianFallback(valid)
            kept = survivors
        }
        return leastSquares(kept) ?: medianFallback(valid)
    }

    /**
     * Proximité → mètres avec une calibration affine ; `null` si une
     * entrée est inexploitable ou si le résultat n'est pas strictement
     * positif fini.
     */
    fun apply(closeness: Float, cal: AffineCalibration): Float? {
        if (!closeness.isFinite() || closeness <= 0f) return null
        if (!cal.scale.isFinite() || cal.scale <= 0f) return null
        if (!cal.shift.isFinite()) return null
        val meters = cal.scale / closeness + cal.shift
        if (!meters.isFinite() || meters <= 0f) return null
        return meters
    }

    /** Moindres carrés sur (1/proximité → mètres) ; `null` si dégénéré. */
    private fun leastSquares(anchors: List<DepthAnchor>): AffineCalibration? {
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (anchor in anchors) {
            val x = 1.0 / anchor.closeness
            val y = anchor.meters.toDouble()
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }
        val n = anchors.size.toDouble()
        val denom = n * sumXX - sumX * sumX
        if (!denom.isFinite() || denom <= 0.0) return null
        val slope = (n * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / n
        val scale = slope.toFloat()
        val shift = intercept.toFloat()
        if (!scale.isFinite() || !shift.isFinite() || scale <= 0f) return null
        return AffineCalibration(scale, shift)
    }

    /** Vrai si l'ancre suit l'ajustement à 30 % relatifs près. */
    private fun residualOk(anchor: DepthAnchor, fit: AffineCalibration): Boolean {
        val predicted = apply(anchor.closeness, fit) ?: return false
        return kotlin.math.abs(predicted - anchor.meters) <= OUTLIER_RESIDUAL * anchor.meters
    }

    /** Repli historique : échelle médiane, décalage nul. */
    private fun medianFallback(anchors: List<DepthAnchor>): AffineCalibration? {
        val scale = DepthReadings.scaleFromAnchors(anchors) ?: return null
        return AffineCalibration(scale, 0f)
    }
}
