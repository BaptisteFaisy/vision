package dev.sam3vision.app.depth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Prétraitement du moteur de profondeur (carré 256, comme les démos de
 * référence). Pur Kotlin sur [IntArray] ARGB : pas de `Bitmap` ici,
 * l'appelant redimensionne avant s'il part d'une photo.
 */
internal object DepthPreprocess {

    /** Côté d'entrée du modèle embarqué (DA2 uint8, 256×256). */
    const val INPUT_SIZE = 256

    /**
     * Redimensionnement bilinéaire vers un carré [size]×[size] (écrasement
     * d'aspect assumé, comme les démos : la carte est réalignée à la fusion).
     */
    fun resizeArgbBilinear(src: IntArray, srcWidth: Int, srcHeight: Int, size: Int): IntArray =
        resizeArgbBilinear(src, srcWidth, srcHeight, size, IntArray(size * size))

    /**
     * Même redimensionnement, écrit dans [out] (aucune allocation) : le chemin
     * du direct y verse son tampon réutilisé ([DepthScratch]).
     */
    fun resizeArgbBilinear(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        size: Int,
        out: IntArray,
    ): IntArray {
        require(srcWidth > 0 && srcHeight > 0 && size > 0) { "dimensions vides" }
        require(src.size >= srcWidth * srcHeight) { "tampon trop petit" }
        require(out.size >= size * size) { "sortie trop petite" }
        if (srcWidth == size && srcHeight == size) {
            src.copyInto(out, 0, 0, size * size)
            return out
        }
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
    fun normalizeCHW(argb: IntArray, size: Int, mean: FloatArray, std: FloatArray): FloatBuffer =
        normalizeCHW(argb, size, mean, std, directFloats(size * size * 3))

    /**
     * Même normalisation, écrite dans [out] (aucune allocation) : le chemin du
     * direct y verse son tampon réutilisé ([DepthScratch]). [out] est rembobiné
     * avant et après le remplissage, comme la version allouante.
     */
    fun normalizeCHW(
        argb: IntArray,
        size: Int,
        mean: FloatArray,
        std: FloatArray,
        out: FloatBuffer,
    ): FloatBuffer {
        require(argb.size >= size * size) { "tampon trop petit" }
        require(out.capacity() >= size * size * 3) { "sortie trop petite" }
        out.rewind()
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

    private fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}

/**
 * Tampons de travail du prétraitement profondeur, **un jeu par fil** : le
 * redimensionné ARGB et l'entrée CHW du modèle, réutilisés d'une inférence à
 * l'autre — le prétraitement n'alloue plus par frame (O8 cpu-max).
 *
 * `ThreadLocal` plutôt qu'un jeu par estimateur : `estimate` tourne sur
 * `Dispatchers.Default`, deux inférences peuvent se chevaucher hors du verrou
 * de session — chacune garde alors son jeu, sans contention ni copie.
 */
internal class DepthScratch(private val size: Int = DepthPreprocess.INPUT_SIZE) {
    private val resizedLocal = ThreadLocal.withInitial { IntArray(size * size) }
    private val chwLocal = ThreadLocal.withInitial {
        ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    /** Carré ARGB de travail du fil courant ([size]×[size]). */
    fun resized(): IntArray = resizedLocal.get()

    /** Entrée CHW de travail du fil courant (`3 × [size] × [size]` flottants). */
    fun chw(): FloatBuffer = chwLocal.get()
}
