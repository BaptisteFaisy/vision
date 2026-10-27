package dev.sam3vision.core.crossing

/**
 * Traits d'un crop de feu pour [TrainedLightColor] : histogramme de teinte
 * (8 cases, pixels saturés), fractions rouge/vert globale et par moitié
 * verticale (le bonhomme rouge est en haut, le vert en bas), part de pixels
 * saturés, valeur et saturation moyennes.
 *
 * Formules strictement identiques au harnais d'entraînement (hors dépôt) :
 * tout écart fausse l'arbre. Conversions HSV partagées avec [PedLightColor].
 */
object CrossingFeatures {

    /** Nombre de traits produits (voir [TrainedLightColor.FEATURE_NAMES]). */
    const val COUNT = 17

    private const val BIN_COUNT = 8
    private const val RED_HUE_DEG = 15f
    private const val GREEN_HUE_MIN_DEG = 80f
    private const val GREEN_HUE_MAX_DEG = 175f
    private const val MIN_SATURATION = 0.45f
    private const val MIN_VALUE = 0.35f

    /**
     * Traits du crop [argb] ([width] x [height], ARGB 32 bits, ligne majeure).
     * Tableau vide ou incohérent ⇒ traits nuls (l'appelant répond doute avant).
     */
    fun extract(argb: IntArray, width: Int, height: Int): FloatArray {
        if (width <= 0 || height <= 0 || argb.size != width * height) return FloatArray(COUNT)
        val counts = Counts()
        val half = height / 2
        for (i in argb.indices) {
            counts.add(argb[i], top = i / width < half)
        }
        return counts.toFeatures(argb.size)
    }

    /** Compteurs bruts du crop : un par trait, normalisés par [toFeatures]. */
    private class Counts {
        private val bins = FloatArray(BIN_COUNT)
        private var red = 0
        private var green = 0
        private var redTop = 0
        private var redBottom = 0
        private var greenTop = 0
        private var greenBottom = 0
        private var saturated = 0
        private var valueSum = 0f
        private var satSum = 0f

        /** Un pixel du crop ([top] = moitié haute) : moyennes toujours, couleur si saturé. */
        fun add(pixel: Int, top: Boolean) {
            val hue = PedLightColor.hueDeg(pixel)
            val sat = PedLightColor.saturation(pixel)
            val value = PedLightColor.value(pixel)
            valueSum += value
            satSum += sat
            if (sat < MIN_SATURATION || value < MIN_VALUE) return
            saturated += 1
            bins[minOf(BIN_COUNT - 1, (hue / 45f).toInt())] += 1f
            val isRed = hue <= RED_HUE_DEG || hue >= 360f - RED_HUE_DEG
            val isGreen = hue in GREEN_HUE_MIN_DEG..GREEN_HUE_MAX_DEG
            when {
                isRed -> {
                    red += 1
                    if (top) redTop += 1 else redBottom += 1
                }
                isGreen -> {
                    green += 1
                    if (top) greenTop += 1 else greenBottom += 1
                }
            }
        }

        /** Vecteur de traits normalisé ([total] = pixels du crop, saturés ou non). */
        fun toFeatures(total: Int): FloatArray {
            val out = FloatArray(COUNT)
            val n = total.toFloat()
            val denom = maxOf(1f, saturated.toFloat())
            for (b in 0 until BIN_COUNT) out[b] = bins[b] / denom
            out[8] = redTop / n
            out[9] = redBottom / n
            out[10] = greenTop / n
            out[11] = greenBottom / n
            out[12] = red / n
            out[13] = green / n
            out[14] = saturated / n
            out[15] = valueSum / n
            out[16] = satSum / n
            return out
        }
    }
}
