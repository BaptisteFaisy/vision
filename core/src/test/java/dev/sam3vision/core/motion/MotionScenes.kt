package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.predict.Box

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

    /** Niveau du rectangle d'avant-plan : entre le fond (40) et les taches (170 à 220). */
    private const val FOREGROUND = 120

    /** Boîte d'avant-plan des scènes véhicule : 64×40, centrée sur le plan 256×103. */
    val VEHICLE_BOX = Box(96f, 32f, 160f, 72f)

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

    /**
     * Paire d'images + boîtes d'un cas véhicule/fond : [prev] et [cur] portent le même
     * rectangle d'avant-plan (le « véhicule ») à [prevBox] et [curBox], sur fond texturé.
     */
    data class VehicleScene(val prev: GrayMask, val cur: GrayMask, val prevBox: Box, val curBox: Box)

    /** Copie de [source] avec le rectangle d'avant-plan peint à [box] ([level] par défaut). */
    fun withForeground(source: GrayMask, box: Box, level: Int = FOREGROUND): GrayMask {
        val mask = GrayMask(source.width, source.height, source.pixels.copyOf())
        val x0 = box.x1.toInt().coerceIn(0, source.width)
        val y0 = box.y1.toInt().coerceIn(0, source.height)
        val x1 = box.x2.toInt().coerceIn(x0, source.width)
        val y1 = box.y2.toInt().coerceIn(y0, source.height)
        fillRect(mask, x0, y0, x1, y1, level)
        return mask
    }

    /** Fond fixe, boîte déplacée de ([dx], [dy]) : le véhicule bouge, la caméra est fixe. */
    fun vehicleMoving(box: Box = VEHICLE_BOX, dx: Int = 12, dy: Int = 0): VehicleScene {
        val moved = box.translated(dx.toFloat(), dy.toFloat())
        return VehicleScene(withForeground(scene(), box), withForeground(scene(), moved), box, moved)
    }

    /** Fond décalé de ([dx], [dy]), boîte fixe : la caméra suit le véhicule (filmer-suivi). */
    fun cameraMoving(box: Box = VEHICLE_BOX, dx: Int = 6, dy: Int = 2): VehicleScene {
        val background = shifted(scene(), dx, dy)
        return VehicleScene(withForeground(scene(), box), withForeground(background, box), box, box)
    }

    /** Fond décalé de ([bgDx], [bgDy]) et boîte déplacée de ([boxDx], [boxDy]) : les deux bougent. */
    fun bothMoving(
        box: Box = VEHICLE_BOX,
        bgDx: Int = 6,
        bgDy: Int = 2,
        boxDx: Int = -10,
        boxDy: Int = 4,
    ): VehicleScene {
        val moved = box.translated(boxDx.toFloat(), boxDy.toFloat())
        val background = shifted(scene(), bgDx, bgDy)
        return VehicleScene(withForeground(scene(), box), withForeground(background, moved), box, moved)
    }
}