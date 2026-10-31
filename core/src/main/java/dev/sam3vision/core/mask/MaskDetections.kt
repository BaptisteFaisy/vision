package dev.sam3vision.core.mask

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.TrackedObject

/**
 * Détections du chemin **distances** : comme `toDetections`, plus la bbox du masque
 * ([Detection.maskBox]) quand elle est fiable — hauteur vraie de l'objet et ligne des
 * pieds exacte pour le sténopé et le plan de sol.
 *
 * Seuls les encodages **compacts** sont lus ici (`poly` sans décodage, `bits`/`rle`
 * décodés en pur Kotlin) : le PNG exige le décodeur d'image Android, et ce chemin
 * reste testable sur JVM nue. `png`/`none`/illisible/troué ⇒ `maskBox = null` (repli
 * boîte, comportement d'avant). Les autres appelants gardent `toDetections` : pas de
 * décodage pour qui n'en a pas besoin.
 */
@JvmName("segObjectsToDetectionsWithExtents")
fun List<SegObject>.toDetectionsWithExtents(width: Int, height: Int): List<Detection> =
    map { Detection(it.label, it.score, it.box, width, height, trustedMaskBox(it, width, height)) }

/** Même enrichissement pour les objets du tracker (mêmes champs de masque). */
@JvmName("trackedObjectsToDetectionsWithExtents")
fun List<TrackedObject>.toDetectionsWithExtents(width: Int, height: Int): List<Detection> =
    map { Detection(it.label, it.score, it.box, width, height, trustedMaskBox(it, width, height)) }

/** Bbox exclusive fiable d'un objet segmenté, `null` sinon (voir le KDoc du fichier). */
private fun trustedMaskBox(obj: SegObject, frameW: Int, frameH: Int): List<Float>? =
    trustedMaskBox(obj.maskEncoding, obj.maskPngB64, obj.maskPoly, obj.maskScale, frameW, frameH)

/** Bbox exclusive fiable d'un objet suivi, `null` sinon (voir le KDoc du fichier). */
private fun trustedMaskBox(obj: TrackedObject, frameW: Int, frameH: Int): List<Float>? =
    trustedMaskBox(obj.maskEncoding, obj.maskPngB64, obj.maskPoly, obj.maskScale, frameW, frameH)

private fun trustedMaskBox(
    encoding: String,
    b64: String,
    poly: List<List<Int>>,
    scale: Float,
    frameW: Int,
    frameH: Int,
): List<Float>? {
    if (frameW <= 0 || frameH <= 0) return null
    val extent = when (encoding) {
        MASK_ENCODING_POLY -> extentFromPoly(poly, scale)
        MASK_ENCODING_BITS -> decodedExtent(decodeBitsMask(b64), frameW, frameH)
        MASK_ENCODING_RLE -> decodedExtent(decodeRleGrid(b64, scale, frameW, frameH), frameW, frameH)
        else -> return null
    } ?: return null
    return exclusiveBox(extent, frameW, frameH)
}

/**
 * Étendue d'un masque décodé, ramenée en pixels frame : `null` si le décodage a
 * échoué, si les dimensions ne collent pas à la frame (bits pleine résolution),
 * ou si le masque est troué (fragmenté : l'étendue ne mesure plus l'objet).
 */
private fun decodedExtent(mask: GrayMask?, frameW: Int, frameH: Int): MaskExtent? {
    if (mask == null || mask.width != frameW || mask.height != frameH) return null
    val extent = extentOf(mask) ?: return null
    return extent.takeUnless { isFragmented(mask, extent) }
}

/** Masque RLE décodé sur sa grille réduite puis remis à l'échelle frame. */
private fun decodeRleGrid(b64: String, scale: Float, frameW: Int, frameH: Int): GrayMask? {
    val grid = maskGrid(frameW, frameH, scale) ?: return null
    val small = decodeRleMask(b64, grid.first, grid.second) ?: return null
    if (scale == 1f) return small
    return upscaleToFrame(small, frameW, frameH)
}

/** Étendue inclusive → boîte exclusive `[x1, y1, x2, y2]`, bornée au cadre. */
private fun exclusiveBox(extent: MaskExtent, frameW: Int, frameH: Int): List<Float>? {
    val x1 = extent.leftCol.coerceIn(0, frameW).toFloat()
    val y1 = extent.topRow.coerceIn(0, frameH).toFloat()
    val x2 = (extent.rightCol + 1).coerceIn(0, frameW).toFloat()
    val y2 = (extent.bottomRow + 1).coerceIn(0, frameH).toFloat()
    if (x2 <= x1 || y2 <= y1) return null
    return listOf(x1, y1, x2, y2)
}
