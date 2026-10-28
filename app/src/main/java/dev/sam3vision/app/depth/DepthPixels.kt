package dev.sam3vision.app.depth

import android.graphics.BitmapFactory
import android.util.Base64

/** Frame décodée pour la profondeur : [width] × [height], ARGB 32 bits. */
data class DepthFrame(val width: Int, val height: Int, val argb: IntArray)

/**
 * Extracteur de pixels du direct pour la profondeur : décode la frame JPEG
 * envoyée à SAM, sous-échantillonnée ([LONG_EDGE]). Wrapper plateforme fin
 * (Bitmap), **non testé en JVM** — comme `CrossingPixels`, seule la fusion
 * (`:core`) l'est.
 */
object DepthPixels {

    /** Plus grand côté décodé : la profondeur n'a pas besoin de mégapixels. */
    const val LONG_EDGE = 512

    /** Frame JPEG (base64) décodée ; `null` = image illisible. */
    fun frame(jpegB64: String): DepthFrame? {
        if (jpegB64.isBlank()) return null
        val bytes = try {
            Base64.decode(jpegB64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return DepthFrame(bitmap.width, bitmap.height, pixels)
    }

    /** Sous-échantillonnage en puissance de 2 ramenant le grand côté à [LONG_EDGE]. */
    private fun sampleSize(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        var edge = maxOf(w, h)
        for (i in 0 until 8) {
            if (edge <= LONG_EDGE) break
            sample *= 2
            edge /= 2
        }
        return sample
    }
}
