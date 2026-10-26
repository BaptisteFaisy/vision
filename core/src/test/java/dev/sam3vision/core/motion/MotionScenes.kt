package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask

/**
 * Scènes de luminance synthétiques du banc de mouvement : fond sombre et taches claires
 * **réparties hors du centre** — près du centre, une échelle ne déplace rien et une
 * translation deviendrait indiscernable d'un zoom.
 *
 * Rien de commun avec `MaskFixtures` (masques binaires de u11) : on juge ici un estimateur de
 * mouvement, il faut donc des bords francs et un fond continu, pas un rectangle plein.
 */
internal object MotionScenes {

    /** Taille du plan de luminance du direct (contrat L13 § 2.1). */
    const val WIDTH = 256
    const val HEIGHT = 103

    private const val BACKGROUND = 40

    /** Trame de référence 256×103 : cinq taches asymétriques, niveaux 170 à 220. */
    fun scene(): GrayMask {
        val mask = GrayMask(WIDTH, HEIGHT, ByteArray(WIDTH * HEIGHT) { BACKGROUND.toByte() })
        fillRect(mask, 18, 12, 62, 38, 220)
        fillRect(mask, 176, 16, 238, 52, 190)
        fillRect(mask, 60, 78, 118, 98, 170)
        fillRect(mask, 128, 62, 168, 88, 210)
        fillRect(mask, 4, 60, 40, 96, 200)
        return mask
    }

    /** La même trame décalée de ([dx], [dy]) : `cur(x, y) = prev(x - dx, y - dy)`. */
    fun shifted(source: GrayMask, dx: Int, dy: Int): GrayMask {
        val out = ByteArray(source.pixels.size)
        for (y in 0 until source.height) {
            val sy = y - dy
            if (sy !in 0 until source.height) continue
            for (x in 0 until source.width) {
                val sx = x - dx
                if (sx in 0 until source.width) {
                    out[y * source.width + x] = source.pixels[sy * source.width + sx]
                }
            }
        }
        return GrayMask(source.width, source.height, out)
    }

    /** La même trame agrandie de [scale] autour du centre : `cur(x) = prev(cx + (x - cx) / scale)`. */
    fun zoomed(source: GrayMask, scale: Float): GrayMask {
        val cx = (source.width - 1) / 2f
        val cy = (source.height - 1) / 2f
        val out = ByteArray(source.pixels.size)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val sx = (cx + (x - cx) / scale).toInt()
                val sy = (cy + (y - cy) / scale).toInt()
                if (sx in 0 until source.width && sy in 0 until source.height) {
                    out[y * source.width + x] = source.pixels[sy * source.width + sx]
                }
            }
        }
        return GrayMask(source.width, source.height, out)
    }

    /** La même trame agrandie ×2 (512×206) : la taille de la grille masque de u11. */
    fun doubled(source: GrayMask): GrayMask {
        val width = source.width * 2
        val height = source.height * 2
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = source.pixels[(y / 2) * source.width + (x / 2)]
            }
        }
        return GrayMask(width, height, out)
    }

    private fun fillRect(mask: GrayMask, x0: Int, y0: Int, x1: Int, y1: Int, level: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) mask.pixels[y * mask.width + x] = level.toByte()
        }
    }
}