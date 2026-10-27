package dev.sam3vision.core.depth

import dev.sam3vision.core.mask.GrayMask

/**
 * Statistiques de proximité d'un masque : [median] (robuste aux bords mal
 * segmentés), [mean]/[min]/[max] (diagnostic) et [count] (pixels d'objet).
 * Proximité = sortie brute des modèles (grand = proche), pas des mètres.
 */
data class MaskDepthStats(
    val count: Int,
    val median: Float,
    val mean: Float,
    val min: Float,
    val max: Float,
)

/** Fusion masque SAM × carte de profondeur, pure Kotlin (miroir de `MaskCore`). */
object DepthFusion {

    /**
     * Statistiques de [depth] restreintes aux pixels d'objet de [mask] (seuil 128,
     * comme `GrayMask.isObject`). La carte est réalignée sur le masque si besoin
     * (bilinéaire). `null` si le masque est vide ou sans objet.
     */
    fun maskedStats(mask: GrayMask, depth: DepthMap): MaskDepthStats? {
        if (mask.size <= 0 || mask.size != mask.pixels.size) return null
        val aligned = alignedDepth(mask, depth)
        var count = 0
        var sum = 0.0
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        val kept = FloatArray(mask.size)
        for (i in 0 until mask.size) {
            if ((mask.pixels[i].toInt() and 0xFF) < 128) continue
            val v = aligned.values[i]
            if (!v.isFinite()) continue
            kept[count++] = v
            sum += v
            if (v < min) min = v
            if (v > max) max = v
        }
        if (count == 0) return null
        return MaskDepthStats(count, quickselectMedian(kept, count), (sum / count).toFloat(), min, max)
    }

    /** Carte réalignée sur le masque (bilinéaire), ou la carte telle quelle si déjà alignée. */
    private fun alignedDepth(mask: GrayMask, depth: DepthMap): DepthMap {
        if (depth.width == mask.width && depth.height == mask.height) return depth
        return depth.resampledTo(mask.width, mask.height)
    }

    /**
     * Médiane des [count] premières cases (quickselect, O(n) : un tri complet
     * coûterait ~100 ms sur un masque plein cadre). Le préfixe est réordonné.
     * Les boucles sont des `for` bornés + `break` (jamais de `while`) : le
     * parseur du scanner UBS ne suit pas les `while` ni les `if`-expressions.
     */
    fun quickselectMedian(values: FloatArray, count: Int): Float {
        require(count in 1..values.size) { "compte hors bornes : $count" }
        val target = (count - 1) / 2
        var low = 0
        var high = count - 1
        for (pass in 0 until count) {
            if (low >= high) break
            val pivot = medianOfThree(values, low, (low + high) / 2, high)
            val (i, j) = partition(values, low, high, pivot)
            if (j < target) low = i
            if (target < i) high = j
        }
        return values[target]
    }

    /** Partition de Hoare sur [low]..[high] : renvoie les curseurs croisés. */
    private fun partition(values: FloatArray, low: Int, high: Int, pivot: Float): Pair<Int, Int> {
        var i = low
        var j = high
        for (step in 0 until (high - low + 1)) {
            if (i > j) break
            i = scanUp(values, i, j, pivot)
            j = scanDown(values, i, j, pivot)
            if (i > j) break
            val tmp = values[i]
            values[i] = values[j]
            values[j] = tmp
            i++
            j--
        }
        return i to j
    }

    /** Avance [i] jusqu'à une valeur >= [pivot] (le pivot, présent, borne la montée). */
    private fun scanUp(values: FloatArray, from: Int, limit: Int, pivot: Float): Int {
        var i = from
        for (step in from..limit) {
            if (values[i] >= pivot) break
            i++
        }
        return i
    }

    /** Recule [j] jusqu'à une valeur <= [pivot] (le pivot, présent, borne la descente). */
    private fun scanDown(values: FloatArray, limit: Int, from: Int, pivot: Float): Int {
        var j = from
        for (step in limit..from) {
            if (values[j] <= pivot) break
            j--
        }
        return j
    }

    private fun medianOfThree(values: FloatArray, a: Int, b: Int, c: Int): Float {
        val x = values[a]
        val y = values[b]
        val z = values[c]
        if (x < y) {
            if (y < z) return y
            if (x < z) return z
            return x
        }
        if (x < z) return x
        if (y < z) return z
        return y
    }

    /**
     * Ordre de proximité : négatif si [a] est devant [b] (médiane plus grande).
     * Sert au tri proche→loin des pastilles et aux couches d'occlusion.
     */
    fun nearerFirst(a: MaskDepthStats, b: MaskDepthStats): Int =
        b.median.compareTo(a.median)

    /**
     * Vrai si [front] masque [back] : médiane franchement plus grande (marge
     * relative [margin], 10 % par défaut — en deçà, c'est du bruit de modèle).
     */
    fun occludes(front: MaskDepthStats, back: MaskDepthStats, margin: Float = 0.1f): Boolean {
        if (front.median <= 0f || back.median <= 0f) return false
        return front.median > back.median * (1f + margin)
    }

    /**
     * Échelle métrique depuis une ancre sténopé : un objet à [anchorMeters]
     * (mesure [Pinhole][dev.sam3vision.core.distance.Pinhole]) de proximité
     * médiane [anchorCloseness] fixe `mètres = échelle / proximité`.
     * `null` si l'ancre est inexploitable.
     */
    fun calibrateScale(anchorMeters: Float, anchorCloseness: Float): Float? {
        if (!anchorMeters.isFinite() || !anchorCloseness.isFinite()) return null
        if (anchorMeters <= 0f || anchorCloseness <= 0f) return null
        return anchorMeters * anchorCloseness
    }

    /** Proximité → mètres avec une échelle calibrée ; `null` si hors bornes. */
    fun closenessToMeters(closeness: Float, scale: Float): Float? {
        if (!closeness.isFinite() || closeness <= 0f || !scale.isFinite() || scale <= 0f) {
            return null
        }
        return scale / closeness
    }
}
