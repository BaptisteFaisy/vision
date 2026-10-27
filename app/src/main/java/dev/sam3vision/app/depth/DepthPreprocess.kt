package dev.sam3vision.app.depth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Prétraitement commun aux deux moteurs de profondeur (carré 256, comme les
 * démos de référence). Pur Kotlin sur [IntArray] ARGB : pas de `Bitmap` ici,
 * l'appelant redimensionne avant s'il part d'une photo.
 */
internal object DepthPreprocess {

    /** Côté d'entrée des deux modèles embarqués (DA2 uint8 et MiDaS small). */
    const val INPUT_SIZE = 256

    /**
     * Redimensionnement bilinéaire vers un carré [size]×[size] (écrasement
     * d'aspect assumé, comme les démos : la carte est réalignée à la fusion).
     */
    fun resizeArgbBilinear(src: IntArray, srcWidth: Int, srcHeight: Int, size: Int): IntArray {
        require(srcWidth > 0 && srcHeight > 0 && size > 0) { "dimensions vides" }
        require(src.size >= srcWidth * srcHeight) { "tampon trop petit" }
        if (srcWidth == size && srcHeight == size) return src.copyOf(size * size)
        val out = IntArray(size * size)
        val scaleX = (srcWidth - 1f) / (size - 1f)
        val scaleY = (srcHeight - 1f) / (size - 1f)
        for (y in 0 until size) {
            for (x in 0 until size) {
                out[y * size + x] = sampleBilinear(src, srcWidth, srcHeight, x * scaleX, y * scaleY)
            }
        }
        return out
    }

    private fun sampleBilinear(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        fx: Float,
        fy: Float,
    ): Int {
        val x0 = fx.toInt().coerceIn(0, srcWidth - 1)
        val y0 = fy.toInt().coerceIn(0, srcHeight - 1)
        val x1 = (x0 + 1).coerceIn(0, srcWidth - 1)
        val y1 = (y0 + 1).coerceIn(0, srcHeight - 1)
        val tx = (fx - fx.toInt()).coerceIn(0f, 1f)
        val ty = (fy - fy.toInt()).coerceIn(0f, 1f)
        val top = mixArgb(src[y0 * srcWidth + x0], src[y0 * srcWidth + x1], tx)
        val bottom = mixArgb(src[y1 * srcWidth + x0], src[y1 * srcWidth + x1], tx)
        return mixArgb(top, bottom, ty)
    }

    private fun mixArgb(a: Int, b: Int, t: Float): Int {
        val r = channel(a, 16) * (1f - t) + channel(b, 16) * t
        val g = channel(a, 8) * (1f - t) + channel(b, 8) * t
        val bl = channel(a, 0) * (1f - t) + channel(b, 0) * t
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
    }

    private fun channel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF).toFloat()

    /**
     * Normalisation ImageNet vers un tampon CHW float32 (entrée ONNX) :
     * `(px/255 - moyenne) / écart` par canal (moyennes/écarts du
     * `preprocessor_config.json` de Depth Anything V2).
     */
    fun normalizeCHW(argb: IntArray, size: Int, mean: FloatArray, std: FloatArray): FloatBuffer {
        require(argb.size >= size * size) { "tampon trop petit" }
        val out = directFloats(size * size * 3)
        for (c in 0 until 3) {
            val shift = (2 - c) * 8
            for (i in 0 until size * size) {
                val px = ((argb[i] shr shift) and 0xFF) / 255f
                out.put((px - mean[c]) / std[c])
            }
        }
        out.rewind()
        return out
    }

    /**
     * Normalisation MiDaS vers un tampon NHWC float32 (entrée LiteRT) :
     * `(px/255 - 0.5) / 0.5` par canal, pixels dans l'ordre R,G,B.
     */
    fun normalizeHWC(argb: IntArray, size: Int): FloatBuffer {
        require(argb.size >= size * size) { "tampon trop petit" }
        val out = directFloats(size * size * 3)
        for (i in 0 until size * size) {
            val px = argb[i]
            out.put((((px shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)
            out.put((((px shr 8) and 0xFF) / 255f - 0.5f) / 0.5f)
            out.put((((px) and 0xFF) / 255f - 0.5f) / 0.5f)
        }
        out.rewind()
        return out
    }

    private fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
