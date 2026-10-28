package dev.sam3vision.core.crossing

/**
 * Porte anti-confusion du vert piéton : un vert classifié ne devient un ordre
 * de traverser que s'il ressemble à un bonhomme — dans le bas du cadre,
 * élancé, sans rouge franc mélangé. Un feu de voiture (disque rond), un
 * reflet ou un crop serré retombe en doute : la porte ne fabrique jamais de
 * vert, elle n'en refuse que.
 *
 * Priors structurels (position + forme), **pas une calibration** : aucune
 * donnée réelle n'est requise ni utilisée, et les constantes ci-dessous sont
 * des ordres de grandeur à recaler sur feux français quand le corpus
 * existera. En attendant, la direction est sûre : tout rejet vaut « attendez ».
 * Pur Kotlin, testé en JVM sur des pixels synthétiques.
 */
object PedLightGate {

    /** Part minimale du vert dans la moitié basse (le bonhomme vert est en bas). */
    private const val GREEN_BOTTOM_MIN = 0.7f

    /** Rouge franc toléré avec un vert (au-delà : signal mélangé, doute). */
    private const val RED_MAX_FRACTION = 0.02f

    /** Vert minimal dans le cadre (en dessous : reflet ou bruit). */
    private const val GREEN_MIN_FRACTION = 0.02f

    /** Élancement minimal de la tache verte (un disque est aussi large que haut). */
    private const val ELONGATION_MIN = 1.15f

    /** Hauteur minimale de la tache verte, en pixels (pas un point). */
    private const val BBOX_MIN_PX = 3

    /**
     * Verdict sûr du crop [argb] ([width] x [height]) : le vert de l'arbre ne
     * passe que la porte, le reste (rouge, doute) passe tel quel — ces deux-là
     * disent déjà « attendez ».
     */
    fun verdict(argb: IntArray, width: Int, height: Int): CrossingState {
        val raw = TrainedLightColor.classify(argb, width, height)
        if (raw != CrossingState.Green) return raw
        if (acceptGreen(argb, width, height)) return CrossingState.Green
        return CrossingState.Unknown
    }

    /** Vrai si le vert du crop a la position et la forme d'un bonhomme. */
    fun acceptGreen(argb: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argb.size != width * height) return false
        val scan = scan(argb, width, height)
        val total = argb.size.toFloat()
        if (scan.green / total < GREEN_MIN_FRACTION) return false
        if (scan.greenBottom.toFloat() / scan.green < GREEN_BOTTOM_MIN) return false
        if (scan.red / total >= RED_MAX_FRACTION) return false
        val boxWidth = scan.maxX - scan.minX + 1
        val boxHeight = scan.maxY - scan.minY + 1
        if (boxHeight < BBOX_MIN_PX) return false
        if (boxHeight.toFloat() < boxWidth * ELONGATION_MIN) return false
        return true
    }

    /** Balayage du crop : compteurs de couleur et boîte englobante du vert. */
    private fun scan(argb: IntArray, width: Int, height: Int): Scan {
        val scan = Scan()
        val half = height / 2
        for (index in argb.indices) {
            val pixel = argb[index]
            if (!isLit(pixel)) continue
            val hue = PedLightColor.hueDeg(pixel)
            if (isRedHue(hue)) {
                scan.red += 1
            } else if (isGreenHue(hue)) {
                scan.green += 1
                if (index / width >= half) scan.greenBottom += 1
                scan.minX = minOf(scan.minX, index % width)
                scan.maxX = maxOf(scan.maxX, index % width)
                scan.minY = minOf(scan.minY, index / width)
                scan.maxY = maxOf(scan.maxY, index / width)
            }
        }
        return scan
    }

    /** Pixel allumé franc (mêmes planchers que [PedLightColor]). */
    private fun isLit(pixel: Int): Boolean {
        if (PedLightColor.saturation(pixel) < PedLightColor.MIN_SATURATION) return false
        if (PedLightColor.value(pixel) < PedLightColor.MIN_VALUE) return false
        return true
    }

    private fun isRedHue(hue: Float): Boolean {
        if (hue <= PedLightColor.RED_HUE_DEG) return true
        return hue >= 360f - PedLightColor.RED_HUE_DEG
    }

    private fun isGreenHue(hue: Float): Boolean {
        if (hue < PedLightColor.GREEN_HUE_MIN_DEG) return false
        return hue <= PedLightColor.GREEN_HUE_MAX_DEG
    }

    /** Compteurs du balayage, boîte du vert en coordonnées pixels. */
    private class Scan(
        var green: Int = 0,
        var greenBottom: Int = 0,
        var red: Int = 0,
        var minX: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxY: Int = Int.MIN_VALUE,
    )
}
