package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import kotlin.math.sin

/**
 * Scènes partagées des tests de block-matching ([BlockShiftTest], [MaskShiftTest]) :
 * une texture déterministe et son constructeur d'images, en un seul endroit.
 */
internal object ShiftScenes {

    /** Texture riche mais lisse, comme une vraie image : le SAD y est unimodal. */
    fun texture(x: Int, y: Int): Int {
        val v = 60 + 45 * sin(x * 0.63) + 40 * sin(y * 0.87) +
            28 * sin((x + y) * 0.29) + 18 * sin(x * 1.9 + y * 0.7)
        return v.toInt().coerceIn(0, 255)
    }

    /** Image texturée [width]×[height]. */
    fun image(width: Int, height: Int): GrayMask = image(width, height) { x, y -> texture(x, y) }

    /** Image [width]×[height] construite pixel par pixel. */
    fun image(width: Int, height: Int, f: (Int, Int) -> Int): GrayMask {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = f(x, y).toByte()
            }
        }
        return GrayMask(width, height, pixels)
    }
}
