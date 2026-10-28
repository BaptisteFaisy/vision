package dev.sam3vision.core.stairs

import dev.sam3vision.core.mask.GrayMask
import kotlin.math.tan

/**
 * Canny pur JVM (flou gaussien 5×5 séparable, Sobel, suppression des
 * non-maxima, hystérésis), restreint à la ROI et aux contours
 * quasi-horizontaux — le port des étapes 3-4 de `stair_detector.py`
 * (`GaussianBlur` + `Canny(50, 150)`, MIT, voir `NOTICE` racine).
 *
 * Un contour quasi-horizontal a son gradient quasi-vertical : le test se fait
 * sur le rapport `|gy| / |gx|`, sans `atan2` par pixel. La magnitude est la
 * norme L1 (`|gx| + |gy|`, comme OpenCV sans `L2gradient`), pour que les
 * seuils 50/150 gardent leur sens d'origine.
 *
 * Fonction totale : image vide ou incohérente = carte vide, jamais d'exception.
 */
object StairsCanny {

    /** Noyau gaussien 5×5 (σ implicite d'OpenCV pour cette taille), somme 16. */
    private val BLUR_KERNEL = intArrayOf(1, 4, 6, 4, 1)

    private const val BLUR_DIVISOR = 16
    private const val BLUR_RADIUS = 2

    /** `tan(67,5°)` en millième : frontière des secteurs NMS à 45°. */
    private const val NMS_SLOPE = 2414

    /** Marqueur « contour fort » dans la carte de magnitude affinée. */
    private const val STRONG = -1

    /**
     * Contours quasi-horizontaux de [mask] entre les lignes [top] (inclus) et
     * [bottom] (exclu) : tableau plein format, `1` sur un contour retenu, `0`
     * ailleurs (dont hors ROI). [config] fournit les seuils de Canny et la
     * tolérance d'angle.
     */
    fun horizontalEdges(mask: GrayMask, top: Int, bottom: Int, config: StairsConfig): ByteArray {
        val width = mask.width
        val height = mask.height
        val out = ByteArray(width * height)
        if (width <= 0 || height <= 0 || mask.pixels.size < width * height) return out
        val regionTop = top.coerceIn(0, height)
        val regionBottom = bottom.coerceIn(regionTop, height)
        if (regionBottom - regionTop < 2 || width < 2) return out
        val blurred = blur(mask)
        val gradients = sobel(blurred, width, height, regionTop, regionBottom)
        val thin = thin(gradients.gx, gradients.gy, width, height, regionTop, regionBottom, config)
        link(thin, width, height, regionTop, regionBottom, config, out)
        flagHorizontal(gradients, width, height, regionTop, regionBottom, config, out)
        return out
    }

    /** Gradients de Sobel plein format (zéro hors ROI). */
    private class Gradients(val gx: IntArray, val gy: IntArray)

    /** Flou gaussien 5×5 séparable, bords dupliqués (miroir de `GaussianBlur`). */
    private fun blur(mask: GrayMask): IntArray {
        val width = mask.width
        val height = mask.height
        val pixels = mask.pixels
        val tmp = IntArray(width * height)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0
                for (k in -BLUR_RADIUS..BLUR_RADIUS) {
                    acc += level(pixels, y * width + x.coerceInClamped(k, width)) * BLUR_KERNEL[k + BLUR_RADIUS]
                }
                tmp[y * width + x] = (acc + BLUR_DIVISOR / 2) / BLUR_DIVISOR
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0
                for (k in -BLUR_RADIUS..BLUR_RADIUS) {
                    acc += tmp[y.coerceInClamped(k, height) * width + x] * BLUR_KERNEL[k + BLUR_RADIUS]
                }
                out[y * width + x] = (acc + BLUR_DIVISOR / 2) / BLUR_DIVISOR
            }
        }
        return out
    }

    /** Niveau 0-255 de l'octet signé à [index]. */
    private fun level(pixels: ByteArray, index: Int): Int = pixels[index].toInt() and 0xFF

    /** Coordonnée décalée de [delta], bornée à `[0, max)` (bord dupliqué). */
    private fun Int.coerceInClamped(delta: Int, max: Int): Int = (this + delta).coerceIn(0, max - 1)

    /** Sobel 3×3 sur la ROI (L1, bords dupliqués). */
    private fun sobel(blurred: IntArray, width: Int, height: Int, top: Int, bottom: Int): Gradients {
        val gx = IntArray(width * height)
        val gy = IntArray(width * height)
        for (y in top until bottom) {
            val up = (y - 1).coerceAtLeast(0) * width
            val mid = y * width
            val down = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val left = (x - 1).coerceAtLeast(0)
                val right = (x + 1).coerceAtMost(width - 1)
                gx[mid + x] = sobelX(blurred, up, mid, down, left, right)
                gy[mid + x] = sobelY(blurred, up, mid, down, left, right, x)
            }
        }
        return Gradients(gx, gy)
    }

    /** Composante horizontale de Sobel sur les trois lignes déjà résolues. */
    private fun sobelX(b: IntArray, up: Int, mid: Int, down: Int, left: Int, right: Int): Int =
        (b[up + right] - b[up + left]) + 2 * (b[mid + right] - b[mid + left]) + (b[down + right] - b[down + left])

    /** Composante verticale de Sobel sur les trois lignes déjà résolues. */
    private fun sobelY(b: IntArray, up: Int, mid: Int, down: Int, left: Int, right: Int, x: Int): Int =
        (b[down + left] - b[up + left]) + 2 * (b[down + x] - b[up + x]) + (b[down + right] - b[up + right])

    /**
     * Suppression des non-maxima : magnitude L1 gardée si elle est un maximum
     * local le long du gradient (4 secteurs à 45°), `0` sinon. Les pixels sous
     * le seuil bas sont déjà écartés ici.
     */
    private fun thin(
        gx: IntArray,
        gy: IntArray,
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        config: StairsConfig,
    ): IntArray {
        val thin = IntArray(width * height)
        for (y in top until bottom) {
            for (x in 0 until width) {
                val index = y * width + x
                val mag = kotlin.math.abs(gx[index]) + kotlin.math.abs(gy[index])
                if (mag < config.cannyLow) continue
                val neighbors = nmsNeighbors(gx[index], gy[index])
                val first = thinMag(gx, gy, width, height, x + neighbors[0], y + neighbors[1])
                val second = thinMag(gx, gy, width, height, x + neighbors[2], y + neighbors[3])
                if (mag >= first && mag >= second) thin[index] = mag
            }
        }
        return thin
    }

    /** Voisins à comparer selon le secteur du gradient (retour : dx1, dy1, dx2, dy2). */
    private fun nmsNeighbors(gx: Int, gy: Int): IntArray {
        val ax = kotlin.math.abs(gx)
        val ay = kotlin.math.abs(gy)
        // Gradient quasi-horizontal : contour vertical, voisins est/ouest.
        if (ax * 1000 >= ay * NMS_SLOPE) return intArrayOf(-1, 0, 1, 0)
        // Gradient quasi-vertical : contour horizontal, voisins nord/sud.
        if (ay * 1000 >= ax * NMS_SLOPE) return intArrayOf(0, -1, 0, 1)
        // Diagonales : le signe de gx*gy choisit entre NE/SO et NO/SE.
        return if ((gx > 0) == (gy > 0)) intArrayOf(-1, -1, 1, 1) else intArrayOf(1, -1, -1, 1)
    }

    /** Magnitude L1 du pixel (x, y), `0` hors image. */
    private fun thinMag(gx: IntArray, gy: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val index = y * width + x
        return kotlin.math.abs(gx[index]) + kotlin.math.abs(gy[index])
    }

    /**
     * Hystérésis : les pixels ≥ seuil haut marquent [STRONG], puis inondation
     * sur les voisins 8-connexes ≥ seuil bas. Les survivants valent `1` dans [out].
     */
    private fun link(
        thin: IntArray,
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        config: StairsConfig,
        out: ByteArray,
    ) {
        val stack = ArrayDeque<Int>()
        for (y in top until bottom) {
            for (x in 0 until width) {
                val index = y * width + x
                if (thin[index] >= config.cannyHigh && out[index] == 0.toByte()) {
                    thin[index] = STRONG
                    out[index] = 1
                    stack.addLast(index)
                }
            }
        }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val cx = current % width
            val cy = current / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until width || ny !in top until bottom) continue
                    val next = ny * width + nx
                    if (out[next] == 0.toByte() && thin[next] >= config.cannyLow) {
                        out[next] = 1
                        stack.addLast(next)
                    }
                }
            }
        }
    }

    /**
     * Ne garde dans [out] que les contours quasi-horizontaux : gradient à moins
     * de [StairsConfig.angleToleranceDeg] de la verticale, soit
     * `|gy| >= tan(90° - tolérance) * |gx|`.
     */
    private fun flagHorizontal(
        gradients: Gradients,
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        config: StairsConfig,
        out: ByteArray,
    ) {
        val slope = tan(Math.toRadians(90.0 - config.angleToleranceDeg))
        for (y in top until bottom) {
            for (x in 0 until width) {
                val index = y * width + x
                if (out[index] == 0.toByte()) continue
                val vertical = kotlin.math.abs(gradients.gy[index])
                val lateral = kotlin.math.abs(gradients.gx[index])
                if (vertical < slope * lateral) out[index] = 0
            }
        }
    }
}
