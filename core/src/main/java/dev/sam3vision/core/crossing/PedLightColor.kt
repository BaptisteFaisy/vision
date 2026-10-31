package dev.sam3vision.core.crossing

/**
 * Couleur d'un feu piéton lue dans ses pixels : compte les pixels rouges et
 * verts (teinte HSV) dans [argb] et tranche [CrossingState.Red] ou
 * [CrossingState.Green] — binaire, tout non-vert attend (ordre user du 31/10).
 *
 * L'appelant a déjà cadré le feu (crop de la boîte SAM, voir `:app`) : ce
 * classifieur ne fait que la couleur, sans Android ni Bitmap — pur Kotlin,
 * testé en JVM sur des pixels synthétiques.
 *
 * Les seuils ci-dessous sont des ordres de grandeur **non calibrés sur
 * appareil** (aucune mesure téléphone dans cet arbre) : une passe de calibrage
 * sur feux français (jour, nuit, pluie) reste à faire avant tout usage réel.
 */
object PedLightColor {

    /** Teinte rouge : en dessous (côté 0°) ou au dessus (côté 360°). */
    internal const val RED_HUE_DEG = 15f

    /** Plage de teinte du vert piéton : les LED françaises montent au cyan (~180°, lot téléphone 31/10), le bleu (240°) restant exclu. */
    internal const val GREEN_HUE_MIN_DEG = 80f
    internal const val GREEN_HUE_MAX_DEG = 195f

    /** Saturation et valeur minimales : un feu allumé est franc, pas pastel. */
    internal const val MIN_SATURATION = 0.45f
    internal const val MIN_VALUE = 0.35f

    /** Délavage du jour : une lampe surexposée (valeur ≥ 0,90) compte dès 0,15 de saturation — les gris (sat 0) restant exclus. */
    internal const val WASHOUT_MIN_SATURATION = 0.15f
    internal const val WASHOUT_MIN_VALUE = 0.90f

    /** Fraction de pixels colorés exigée pour trancher (un bonhomme dans sa boîte). */
    private const val MIN_FRACTION = 0.06f

    /** Marge exigée entre les deux camps : pas de verdict sur un match nul. */
    private const val MARGIN_RATIO = 2.0f

    /**
     * État du feu cadré par [argb] ([width] × [height], ARGB 32 bits, ligne
     * majeure). Tableau vide ou dimensions incohérentes ⇒ [CrossingState.Red]
     * (une entrée illisible attend, comme un doute).
     */
    fun classify(argb: IntArray, width: Int, height: Int): CrossingState {
        if (width <= 0 || height <= 0 || argb.size != width * height) return CrossingState.Red
        var red = 0
        var green = 0
        for (pixel in argb) {
            if (!isLit(pixel)) continue
            val hue = hueDeg(pixel)
            if (hue <= RED_HUE_DEG || hue >= 360f - RED_HUE_DEG) red += 1
            else if (hue in GREEN_HUE_MIN_DEG..GREEN_HUE_MAX_DEG) green += 1
        }
        val total = argb.size.toFloat()
        val redFraction = red / total
        val greenFraction = green / total
        if (redFraction >= MIN_FRACTION && redFraction >= greenFraction * MARGIN_RATIO) {
            return CrossingState.Red
        }
        if (greenFraction >= MIN_FRACTION && greenFraction >= redFraction * MARGIN_RATIO) {
            return CrossingState.Green
        }
        return CrossingState.Red
    }

    /**
     * Pixel retenu par le pipeline (traits, porte, classifieur HSV) : franc
     * (saturation ≥ [MIN_SATURATION]) ou délavé par le soleil (très brillant,
     * saturation ≥ [WASHOUT_MIN_SATURATION]) — une lampe blanche de jour reste
     * une lampe. Toujours éteint sous [MIN_VALUE]. Seule définition du lot
     * (porte et traits l'appellent, pas de copie).
     */
    internal fun isLit(pixel: Int): Boolean {
        val value = value(pixel)
        if (value < MIN_VALUE) return false
        val sat = saturation(pixel)
        if (sat >= MIN_SATURATION) return true
        return sat >= WASHOUT_MIN_SATURATION && value >= WASHOUT_MIN_VALUE
    }

    /** Teinte 0..360° (conversion maison : pas d'Android en `:core`). */
    internal fun hueDeg(pixel: Int): Float {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta <= 0f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    internal fun saturation(pixel: Int): Float {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val max = maxOf(r, g, b)
        if (max <= 0f) return 0f
        return (max - minOf(r, g, b)) / max
    }

    internal fun value(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return maxOf(r, g, b) / 255f
    }
}
