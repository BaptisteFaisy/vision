package dev.sam3vision.app.isee

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.sam3vision.core.crossing.PedLightColor
import kotlin.math.roundToInt

/**
 * Pixels d'un feu cadré, prêts pour
 * [dev.sam3vision.core.crossing.PedLightColor] : [width] × [height], ARGB
 * 32 bits, ligne majeure.
 */
data class CrossingFrame(val width: Int, val height: Int, val argb: IntArray)

/**
 * Extracteur de pixels du mode Traversée : décode la frame JPEG envoyée à SAM
 * et croppe la boîte du feu. Wrapper plateforme fin (Bitmap), **non testé en
 * JVM** — comme les wrappers `Masks.kt`, seule la couleur (en `:core`) l'est ;
 * le recentrage lampe ([lampCentroid]) est pur et testé (`CrossingRecenterTest`).
 */
object CrossingPixels {

    /** Plus grand côté du crop classifié (96 px depuis le 31/10 : la lampe y garde sa forme). */
    const val MAX_EDGE = 96

    /**
     * Seuil de recentrage : tant que le centroïde lampe reste dans
     * [RECENTER_MIN_FRACTION] de la boîte autour du centre, on garde la boîte
     * SAM (pas de jitter sur les cadrages déjà serrés).
     */
    const val RECENTER_MIN_FRACTION = 0.12f

    /**
     * Frame JPEG (base64) décodée puis croppée sur [normBox] (`x1,y1,x2,y2` en
     * 0..1, voir `CrossingPrompts.selectLightBox`), recentrée sur la lampe
     * ([lampCentroid]) puis réduite à [MAX_EDGE] px. `null` = image illisible
     * ou boîte hors champ (l'appelant doute, « feu illisible »).
     */
    fun frame(jpegB64: String, normBox: List<Float>): CrossingFrame? {
        if (jpegB64.isBlank() || normBox.size != 4) return null
        val bytes = try {
            Base64.decode(jpegB64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return crop(bitmap, normBox)
    }

    /**
     * Crop [normBox] de [bitmap], recentré lampe, réduit, pixels ARGB
     * extraits. Coût borné (une passe pixels + un recrop au plus, pas de
     * second décodage), à valider sur appareil.
     */
    fun crop(bitmap: Bitmap, normBox: List<Float>): CrossingFrame? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val left = (normBox[0].coerceIn(0f, 1f) * w).roundToInt().coerceIn(0, w - 1)
        val top = (normBox[1].coerceIn(0f, 1f) * h).roundToInt().coerceIn(0, h - 1)
        val right = (normBox[2].coerceIn(0f, 1f) * w).roundToInt().coerceIn(left + 1, w)
        val bottom = (normBox[3].coerceIn(0f, 1f) * h).roundToInt().coerceIn(top + 1, h)
        val box = recenteredBox(bitmap, left, top, right, bottom)
        val boxWidth = box[2] - box[0]
        val boxHeight = box[3] - box[1]
        val scaled = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, box[0], box[1], boxWidth, boxHeight),
            MAX_EDGE.coerceAtMost(boxWidth),
            MAX_EDGE.coerceAtMost(boxHeight),
            false,
        )
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return CrossingFrame(scaled.width, scaled.height, pixels)
    }

    /**
     * Boîte recentrée sur la lampe : même taille que ([left], [top],
     * [right], [bottom]) (px bitmap), centrée sur le [lampCentroid] quand il
     * s'écarte du centre passé [RECENTER_MIN_FRACTION] — sinon la boîte SAM
     * telle quelle. Rognée au bitmap. Sans pixel lampe : pas de recentrage.
     */
    internal fun recenteredBox(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val boxWidth = right - left
        val boxHeight = bottom - top
        if (boxWidth <= 0 || boxHeight <= 0) return intArrayOf(left, top, right, bottom)
        val argb = IntArray(boxWidth * boxHeight)
        bitmap.getPixels(argb, 0, boxWidth, left, top, boxWidth, boxHeight)
        val centroid = lampCentroid(argb, boxWidth, boxHeight)
            ?: return intArrayOf(left, top, right, bottom)
        val dead = RECENTER_MIN_FRACTION * minOf(boxWidth, boxHeight)
        val dx = centroid.first - boxWidth / 2f
        val dy = centroid.second - boxHeight / 2f
        if (kotlin.math.abs(dx) <= dead && kotlin.math.abs(dy) <= dead) {
            return intArrayOf(left, top, right, bottom)
        }
        val newLeft = (left + dx.roundToInt()).coerceIn(0, bitmap.width - boxWidth)
        val newTop = (top + dy.roundToInt()).coerceIn(0, bitmap.height - boxHeight)
        return intArrayOf(newLeft, newTop, newLeft + boxWidth, newTop + boxHeight)
    }

    /**
     * Centroïde des pixels lampe ([PedLightColor.isLampPixel], saturés et
     * francs, mêmes planchers que le classifieur) en coordonnées crop,
     * `null` sans pixel lampe (feu éteint, boîte vide). Pur, testé en JVM.
     */
    internal fun lampCentroid(argb: IntArray, width: Int, height: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || argb.size != width * height) return null
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (index in argb.indices) {
            if (!PedLightColor.isLampPixel(argb[index])) continue
            sumX += (index % width)
            sumY += (index / width)
            count += 1
        }
        if (count == 0) return null
        return (sumX / count).toFloat() to (sumY / count).toFloat()
    }
}
