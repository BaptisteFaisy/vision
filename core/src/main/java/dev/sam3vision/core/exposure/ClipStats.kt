package dev.sam3vision.core.exposure

/**
 * Écrêtage d'un crop de feu : combien de pixels sont **blancs écrasés**, c'est-à-dire à
 * canal max ≥ [CLIP_LEVEL] **et** saturation < [BURN_SATURATION]. Ces pixels sont perdus
 * pour le classifieur : `PedLightColor.isLit` les refuse (ni francs, ni délavés) — la lampe
 * paraît alors éteinte alors qu'elle brûle.
 *
 * Chiffres du dépôt (13 crops téléphone `core/src/test/resources/feuxfr3/`, mesurés le
 * 2026-11-02) : [fraction] va de **0 % à 4,84 %**, moyenne **1,08 %** ; [clipped] (canal
 * max ≥ 250, couleur comprise) plafonne à 3,17 % en moyenne. Un crop sain n'a donc
 * quasiment aucun pixel blanc écrasé, un crop surexposé en a des milliers : c'est un
 * discriminateur net, pas un réglage fin.
 *
 * Pur Kotlin (aucun Android, aucun Bitmap) : calculé en JVM dans [Clipping], testé sur
 * pixels synthétiques, et seule la lecture de compilateur y touche les offsets ARGB.
 */
data class ClipStats(
    /** Pixels à canal max ≥ [CLIP_LEVEL] (toutes couleurs confondues) : la lampe sature. */
    val clipped: Int,
    /** Pixels blancs écrasés (canal max ≥ [CLIP_LEVEL] et saturation < [BURN_SATURATION]). */
    val burnedWhite: Int,
    /** Pixels du crop (dénominateur ; 0 = crop vide ou dimensions incohérentes). */
    val total: Int,
) {
    /** Part du crop écrasée en blanc, 0..1 — `0` sur un crop vide, jamais NaN. */
    val fraction: Float
        get() = if (total <= 0) 0f else burnedWhite.toFloat() / total

    /**
     * Vrai au-delà de [BLOWN_FRACTION] : « la lampe est blanche par surexposition »,
     * à ne pas confondre avec « feu éteint ». Seuil posé **au-dessus de tous les crops
     * sains du dépôt** (max 0,78 %) et **sous les deux pires** (1,91 % et 4,84 %).
     */
    val blown: Boolean
        get() = fraction >= BLOWN_FRACTION

    companion object {
        /** Niveau d'écrêtage : 250 sur 255 laisse passer le bruit de quantification JPEG. */
        const val CLIP_LEVEL = 250

        /** Sous cette saturation, un pixel écrasé n'a plus de teinte : rouge et vert se confondent. */
        const val BURN_SATURATION = 0.15f

        /** Seuil de « cramé » (1,5 % du crop) : entre la population saine et la population écrasée. */
        const val BLOWN_FRACTION = 0.015f

        /** Aucune mesure : l'appelant ne pilote rien (voir `ClipGovernor`). */
        val NONE = ClipStats(0, 0, 0)
    }
}

/**
 * Compteur d'écrêtage d'un crop ARGB ([width] × [height], ligne majeure) — **une seule
 * passe** sur les pixels : 25,5 µs mesurés pour un crop 96 × 96 (JVM desktop, 20 000
 * passes, meilleure des 5 séries) — négligeable devant le décodage JPEG qui l'a produit.
 */
object Clipping {

    /**
     * Statistiques d'écrêtage de [argb]. Dimensions incohérentes (≤ 0 ou tableau trop
     * court) ⇒ [ClipStats.NONE] : l'appelant doute, il ne pilote aucune exposition.
     */
    fun stats(argb: IntArray, width: Int, height: Int): ClipStats {
        if (width <= 0 || height <= 0) return ClipStats.NONE
        val count = width * height
        if (argb.size < count) return ClipStats.NONE
        var clipped = 0
        var burned = 0
        for (index in 0 until count) {
            val pixel = argb[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val max = if (r >= g) maxOf(r, b) else maxOf(g, b)
            if (max < ClipStats.CLIP_LEVEL) continue
            clipped += 1
            val min = if (r <= g) minOf(r, b) else minOf(g, b)
            // Saturation HSV = (max − min) / max, max ≥ 250 : jamais de division par zéro.
            if ((max - min).toFloat() / max < ClipStats.BURN_SATURATION) burned += 1
        }
        return ClipStats(clipped, burned, count)
    }
}
