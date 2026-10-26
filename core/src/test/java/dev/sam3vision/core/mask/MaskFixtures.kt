package dev.sam3vision.core.mask

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Fixtures communes aux trois fichiers de test du warp ([MaskMotionTest],
 * [MaskPropagatorTest], [MaskPropagatorBenchTest]) : fabrication de masques (rectangle,
 * disque) et mesure de qualité (aire, boîte, IoU, rayon équivalent).
 *
 * Un seul exemplaire de cette géométrie : les tests n'en réimplémentent aucun morceau.
 */
internal object MaskFixtures {

    /** Masque gris dont les pixels d'objet sont ceux qui vérifient [objet] (255 = objet). */
    fun gray(width: Int, height: Int, objet: (Int, Int) -> Boolean): GrayMask {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (objet(x, y)) pixels[y * width + x] = 255.toByte()
            }
        }
        return GrayMask(width, height, pixels)
    }

    /** Rectangle plein `[x0, x1[ × [y0, y1[`. */
    fun rect(width: Int, height: Int, x0: Int, y0: Int, x1: Int, y1: Int): GrayMask =
        gray(width, height) { x, y -> x >= x0 && x < x1 && y >= y0 && y < y1 }

    /** Disque (ellipse) plein : centre et rayons en pixels. */
    fun disc(width: Int, height: Int, cx: Double, cy: Double, rx: Double, ry: Double): GrayMask =
        gray(width, height) { x, y ->
            val nx = (x - cx) / rx
            val ny = (y - cy) / ry
            nx * nx + ny * ny <= 1.0
        }

    /** Boîte englobante des pixels d'objet : `intArrayOf(x0, y0, x1, y1)`, `-1` si vide. */
    fun box(mask: GrayMask): IntArray {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (!mask.isObject(x, y)) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) intArrayOf(-1, -1, -1, -1) else intArrayOf(minX, minY, maxX, maxY)
    }

    /** Nombre de pixels d'objet (niveau ≥ 128). */
    fun area(mask: GrayMask): Int {
        var count = 0
        for (level in mask.pixels) {
            if ((level.toInt() and 0xFF) >= 128) count++
        }
        return count
    }

    /** IoU des deux masques, sur les pixels d'objet (seuil 128). `1.0` si les deux sont vides. */
    fun iou(a: GrayMask, b: GrayMask): Double {
        var intersection = 0
        var union = 0
        for (i in a.pixels.indices) {
            val inA = (a.pixels[i].toInt() and 0xFF) >= 128
            val inB = (b.pixels[i].toInt() and 0xFF) >= 128
            if (inA && inB) intersection++
            if (inA || inB) union++
        }
        return if (union == 0) 1.0 else intersection.toDouble() / union
    }

    /** Rayon d'un disque de même aire, en pixels. */
    fun equivalentRadius(area: Int): Double = sqrt(area / PI)

    /** IoU d'un demi-pixel : warp bilinéaire à 0,5 px contre le décalage entier de 1 px. */
    fun iouDemiPixel(propagator: MaskPropagator, src: GrayMask): Double =
        iou(propagator.warp(src, MaskMotion(dx = 0.5))!!, propagator.warp(src, MaskMotion(dx = 1.0))!!)

    /** IoU d'une chaîne de 10 pas de 0,5 px contre le warp direct de 5 px (le flou ne s'accumule pas). */
    fun iouChaine(propagator: MaskPropagator, src: GrayMask): Double {
        var courant = src
        repeat(10) { courant = propagator.warp(courant, MaskMotion(dx = 0.5))!! }
        return iou(courant, propagator.warp(src, MaskMotion(dx = 5.0))!!)
    }
}