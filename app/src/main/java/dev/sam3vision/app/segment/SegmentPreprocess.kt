package dev.sam3vision.app.segment

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import dev.sam3vision.app.depth.DepthPreprocess

/**
 * Prétraitement TwinLiteNet 640×360 : rectangle (le rééchantillonneur commun,
 * pas de doublon) puis tampon NCHW **RGB** float32, `x/255` (contrat du zoo).
 */
object SegmentPreprocess {

    const val INPUT_WIDTH = 640
    const val INPUT_HEIGHT = 360

    /**
     * Rotation + mise à l'échelle en UNE passe native bilinéaire vers
     * 640×360 (écrasement d'aspect assumé, comme la sample app). Remplace
     * redressement + resize Kotlin (deux rééchantillonnages : une passe
     * floue moins, à géométrie identique).
     */
    fun rotateAndFit(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        val bounds = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        matrix.postScale(INPUT_WIDTH / bounds.width(), INPUT_HEIGHT / bounds.height())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** ARGB → 640×360 (identité sans copie si déjà à la taille). */
    fun resizeLandscape(src: IntArray, width: Int, height: Int): IntArray {
        if (width == INPUT_WIDTH && height == INPUT_HEIGHT) return src
        return DepthPreprocess.resizeArgbBilinearRect(src, width, height, INPUT_WIDTH, INPUT_HEIGHT)
    }

    /**
     * 640×360 ARGB → NCHW RGB float32 (entrée `serving_default_args_0`).
     * Écrit direct dans le tableau du tenseur : une passe, une allocation
     * (l'ancien détour par `ByteBuffer` copiait 2,7 Mo deux fois par frame).
     */
    fun normalizeNCHWArray(argb: IntArray): FloatArray {
        val plane = INPUT_WIDTH * INPUT_HEIGHT
        require(argb.size >= plane) { "tampon trop petit" }
        val out = FloatArray(plane * 3)
        for (c in 0 until 3) {
            val shift = (2 - c) * 8 // R = 16, G = 8, B = 0
            val base = c * plane
            for (i in 0 until plane) {
                out[base + i] = ((argb[i] shr shift) and 0xFF) / 255f
            }
        }
        return out
    }
}
