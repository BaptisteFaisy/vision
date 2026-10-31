package dev.sam3vision.core.mask

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Seuil objet d'un niveau gris, le même que [GrayMask.isObject] : un pixel appartient à
 * l'objet dès 128.
 */
const val MASK_GEOMETRY_OBJECT_LEVEL = 128

/**
 * Remplissage minimal bbox pour un masque sain : en dessous, [isFragmented] signale une
 * occultation ou des trous. Un objet plein remplit ~0,5-1,0 de sa bbox ; 0,25 laisse passer
 * les formes creuses (anneau, personne bras écartés) mais pas deux îlots éloignés.
 */
const val MASK_FRAGMENT_MIN_FILL = 0.25f

/**
 * Tolérance par défaut de [touchesBorder] : un objet à 2 px ou moins du bord haut/bas de
 * l'image est considéré comme coupé par le cadrage.
 */
const val MASK_BORDER_TOLERANCE_PX = 2f

/**
 * Étendue d'un objet dans son masque : bbox inclusive ([topRow]..[bottomRow],
 * [leftCol]..[rightCol]) plus le nombre de pixels objet (>= [MASK_GEOMETRY_OBJECT_LEVEL]).
 *
 * [pixels] vaut l'aire de la bbox (borne haute) quand l'étendue vient d'[extentFromPoly] :
 * le remplissage exact exigerait le décodage plein, justement évité — [isFragmented] est
 * donc inopérant sur ces étendues (ratio toujours 1,0).
 */
data class MaskExtent(
    val topRow: Int,
    val bottomRow: Int,
    val leftCol: Int,
    val rightCol: Int,
    val pixels: Int,
)

/**
 * Étendue des pixels objet de [mask] (>= [MASK_GEOMETRY_OBJECT_LEVEL]), ou `null` si le
 * masque est vide, sans objet, ou inutilisable (dimensions nulles, tampon trop court).
 * Un tampon plus long que `width × height` est toléré : seuls les premiers pixels comptent.
 */
fun extentOf(mask: GrayMask): MaskExtent? {
    if (mask.width <= 0 || mask.height <= 0) return null
    val size = mask.width * mask.height
    if (mask.pixels.size < size) return null
    var top = mask.height
    var bottom = -1
    var left = mask.width
    var right = -1
    var pixels = 0
    for (y in 0 until mask.height) {
        for (x in 0 until mask.width) {
            if ((mask.pixels[y * mask.width + x].toInt() and 0xFF) < MASK_GEOMETRY_OBJECT_LEVEL) {
                continue
            }
            pixels++
            if (y < top) top = y
            if (y > bottom) bottom = y
            if (x < left) left = x
            if (x > right) right = x
        }
    }
    if (pixels == 0) return null
    return MaskExtent(top, bottom, left, right, pixels)
}

/** Hauteur de l'objet en pixels (bbox inclusive : `bottom - top + 1`). */
fun heightPx(extent: MaskExtent): Int = extent.bottomRow - extent.topRow + 1

/** Ligne du « pied » de l'objet : sa dernière ligne ([MaskExtent.bottomRow]). */
fun footRow(extent: MaskExtent): Int = extent.bottomRow

/**
 * `true` si l'objet touche le bord haut ou bas de l'image (`w × h`) à [tolerancePx] près :
 * sa hauteur est alors tronquée par le cadrage et ne mesure plus l'objet entier.
 * Dimensions folles ou tolérance négative : `false` (pas d'information, pas d'alarme).
 */
fun touchesBorder(extent: MaskExtent, w: Int, h: Int, tolerancePx: Float = MASK_BORDER_TOLERANCE_PX): Boolean {
    if (w <= 0 || h <= 0 || tolerancePx.isNaN() || tolerancePx < 0f) return false
    return extent.topRow <= tolerancePx || extent.bottomRow >= h - 1 - tolerancePx
}

/**
 * Heuristique de fragmentation : `true` quand le remplissage `pixels / aire bbox` passe
 * sous [MASK_FRAGMENT_MIN_FILL] — typiquement un objet occulté (îlots séparés) ou troué.
 * Une étendue dégénérée (aire nulle) ou un masque vide rend `true` : sans mesure fiable,
 * la distance qui en dériverait ne vaut rien, autant la signaler.
 */
fun isFragmented(mask: GrayMask, extent: MaskExtent): Boolean {
    if (mask.width <= 0 || mask.height <= 0 || extent.pixels <= 0) return true
    val w = extent.rightCol - extent.leftCol + 1
    val h = extent.bottomRow - extent.topRow + 1
    if (w <= 0 || h <= 0) return true
    return extent.pixels.toFloat() / (w * h) < MASK_FRAGMENT_MIN_FILL
}

/**
 * Étendue d'un masque `poly` ([SegObject.maskPoly][dev.sam3vision.core.model.SegObject.maskPoly])
 * **sans décodage plein** : la bbox directe des sommets de tous les anneaux, convertie de la
 * grille du masque vers la frame via [scale] (`mask_scale` = grille / frame, donc on divise).
 * Les bords min prennent le plancher, les bords max le plafond : la bbox englobe les sommets.
 *
 * Chaque anneau est plat (`x0, y0, x1, y1, …`) ; un anneau de moins d'une paire complète est
 * ignoré, une coordonnée impaire orpheline aussi. `null` — jamais d'exception — si aucun
 * sommet valide, si [scale] est nulle/négative/NaN/infinie, ou si la conversion déborde.
 */
fun extentFromPoly(poly: List<List<Int>>, scale: Float): MaskExtent? {
    if (poly.isEmpty() || scale.isNaN() || scale.isInfinite() || scale <= 0f) return null
    var minX: Int? = null
    var maxX: Int? = null
    var minY: Int? = null
    var maxY: Int? = null
    for (ring in poly) {
        val pairs = ring.size / 2
        for (i in 0 until pairs) {
            val x = ring[2 * i]
            val y = ring[2 * i + 1]
            if (minX == null || x < minX) minX = x
            if (maxX == null || x > maxX) maxX = x
            if (minY == null || y < minY) minY = y
            if (maxY == null || y > maxY) maxY = y
        }
    }
    if (minX == null || maxX == null || minY == null || maxY == null) return null
    val left = gridToFrame(minX, scale, ceilIt = false) ?: return null
    val top = gridToFrame(minY, scale, ceilIt = false) ?: return null
    val right = gridToFrame(maxX, scale, ceilIt = true) ?: return null
    val bottom = gridToFrame(maxY, scale, ceilIt = true) ?: return null
    val area = (right - left + 1).toLong() * (bottom - top + 1).toLong()
    if (area <= 0L || area > Int.MAX_VALUE) return null
    return MaskExtent(top, bottom, left, right, area.toInt())
}

/** Une coordonnée grille → frame (`coord / scale`), plancher ou plafond, `null` si déborde. */
private fun gridToFrame(coord: Int, scale: Float, ceilIt: Boolean): Int? {
    val frame = coord.toDouble() / scale.toDouble()
    if (!frame.isFinite()) return null
    val rounded = if (ceilIt) ceil(frame) else floor(frame)
    if (rounded < Int.MIN_VALUE.toDouble() || rounded > Int.MAX_VALUE.toDouble()) return null
    return rounded.toInt()
}
