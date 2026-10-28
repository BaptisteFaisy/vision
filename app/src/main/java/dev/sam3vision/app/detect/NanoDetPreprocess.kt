package dev.sam3vision.app.detect

import dev.sam3vision.app.depth.DepthPreprocess
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Prétraitement NanoDet-Plus 320 : carré 320 (le rééchantillonneur commun de
 * la profondeur, pas de doublon) puis tampon CHW **BGR** float32, moyennes et
 * écarts du `nanodet-plus-m_320.yml` amont sur pixels **0-255** (pas 0-1 :
 * `normalizeCHW` ne convient pas ici).
 */
object NanoDetPreprocess {

    const val INPUT_SIZE = 320

    /** Moyennes B,G,R et écarts B,G,R du pipeline `val` amont. */
    val MEAN_BGR = floatArrayOf(103.53f, 116.28f, 123.675f)
    val STD_BGR = floatArrayOf(57.375f, 57.12f, 58.395f)

    /** ARGB → carré 320 (écrasement d'aspect assumé, comme la démo amont). */
    fun resizeSquare(src: IntArray, width: Int, height: Int): IntArray =
        DepthPreprocess.resizeArgbBilinear(src, width, height, INPUT_SIZE)

    /** Carré 320 ARGB → CHW BGR float32 (entrée ONNX `data`). */
    fun normalizeBGR(argb320: IntArray): FloatBuffer {
        require(argb320.size >= INPUT_SIZE * INPUT_SIZE) { "tampon trop petit" }
        val out = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (c in 0 until 3) {
            val shift = c * 8 // B = 0, G = 8, R = 16
            for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
                val px = ((argb320[i] shr shift) and 0xFF).toFloat()
                out.put((px - MEAN_BGR[c]) / STD_BGR[c])
            }
        }
        out.rewind()
        return out
    }
}
