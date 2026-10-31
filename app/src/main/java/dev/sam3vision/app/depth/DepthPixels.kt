package dev.sam3vision.app.depth

import android.graphics.BitmapFactory
import android.util.Base64

/** Frame décodée pour la profondeur : [width] × [height], ARGB 32 bits. */
data class DepthFrame(val width: Int, val height: Int, val argb: IntArray)

/**
 * Extracteur de pixels du direct pour la profondeur : décode la frame JPEG
 * envoyée à SAM, sous-échantillonnée ([LONG_EDGE]). Wrapper plateforme fin
 * (Bitmap) : le décodage lui-même reste **non testé en JVM** — comme `CrossingPixels`,
 * seule la fusion (`:core`) et le partage ([LastDecode]) le sont.
 */
object DepthPixels {

    /** Plus grand côté décodé : la profondeur n'a pas besoin de mégapixels. */
    const val LONG_EDGE = 512

    /** Dernier décodage, partagé : profondeur et danger reçoivent la même frame. */
    private val lastDecode = LastDecode()

    /**
     * Frame JPEG (base64) décodée ; `null` = image illisible.
     *
     * Décodée une fois : le second consommateur de la même frame relit le [DepthFrame]
     * en cache au lieu de repayer `BitmapFactory`. Le rendu est partagé en **lecture
     * seule** — ne jamais écrire dans `argb`.
     */
    fun frame(jpegB64: String): DepthFrame? {
        if (jpegB64.isBlank()) return null
        lastDecode.get(jpegB64)?.let { return it }
        val decoded = decodeUncached(jpegB64)
        if (decoded != null) lastDecode.put(jpegB64, decoded)
        return decoded
    }

    /** Décodage Bitmap brut, sans cache : un seul appelant ([frame]). */
    private fun decodeUncached(jpegB64: String): DepthFrame? {
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

    /**
     * Dernier décodage JPEG partagé : la profondeur et le danger reçoivent la même
     * frame base64, décodée une fois au lieu de deux (`BitmapFactory` n'est pas donné).
     * Une seule entrée suffit : les deux consommateurs offrent la même frame au même
     * instant. Pur et testé en JVM (le `Bitmap`, lui, reste dans [frame]).
     */
    internal class LastDecode {
        private var key: String? = null
        private var frame: DepthFrame? = null

        /** Dernier décodage si [jpegB64] est la même frame, `null` sinon (à décoder). */
        @Synchronized
        fun get(jpegB64: String): DepthFrame? = if (jpegB64 == key) frame else null

        /** Mémorise le décodage de [jpegB64] pour le second consommateur. */
        @Synchronized
        fun put(jpegB64: String, decoded: DepthFrame) {
            key = jpegB64
            frame = decoded
        }
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
