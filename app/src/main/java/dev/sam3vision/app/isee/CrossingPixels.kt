package dev.sam3vision.app.isee

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
 * JVM** — comme les wrappers `Masks.kt`, seule la couleur (en `:core`) l'est.
 */
object CrossingPixels {

    /** Plus grand côté du crop classifié : un feu n'a pas besoin de mégapixels. */
    const val MAX_EDGE = 48

    /**
     * Frame JPEG (base64) décodée puis croppée sur [normBox] (`x1,y1,x2,y2` en
     * 0..1, voir `CrossingPrompts.selectLightBox`), réduite à [MAX_EDGE] px.
     * `null` = image illisible ou boîte hors champ (l'appelant annonce un doute).
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

    /** Crop [normBox] de [bitmap], réduit, pixels ARGB extraits. */
    fun crop(bitmap: Bitmap, normBox: List<Float>): CrossingFrame? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val left = (normBox[0].coerceIn(0f, 1f) * w).roundToInt().coerceIn(0, w - 1)
        val top = (normBox[1].coerceIn(0f, 1f) * h).roundToInt().coerceIn(0, h - 1)
        val right = (normBox[2].coerceIn(0f, 1f) * w).roundToInt().coerceIn(left + 1, w)
        val bottom = (normBox[3].coerceIn(0f, 1f) * h).roundToInt().coerceIn(top + 1, h)
        val scaled = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top),
            MAX_EDGE.coerceAtMost(right - left),
            MAX_EDGE.coerceAtMost(bottom - top),
            false,
        )
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return CrossingFrame(scaled.width, scaled.height, pixels)
    }
}
