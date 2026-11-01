package dev.sam3vision.core.mask

import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject

/**
 * Masque d'un objet du direct, **dans la grille où le serveur l'a envoyé** : point d'entrée
 * unique des quatre encodages du codec (`png`, `bits`, `rle`, `poly`) plus `none`.
 * Les formats compacts se relisent sur la grille réduite annoncée par `maskScale` — un run
 * RLE ne porte aucune dimension, et un anneau `poly` est exprimé dans cette grille.
 *
 * `null` si le masque est illisible ou absent (`"none"`) : l'appelant dessine alors la boîte.
 */
fun decodeObjectMask(
    obj: SegObject,
    frameWidth: Int,
    frameHeight: Int,
    probe: DecodeProbe? = null,
): GrayMask? =
    when (obj.maskEncoding) {
        MASK_ENCODING_RLE -> compactGrid(obj, frameWidth, frameHeight)
            ?.let { decodeRleMask(obj.maskPngB64, it.first, it.second, probe) }

        MASK_ENCODING_POLY -> compactGrid(obj, frameWidth, frameHeight)
            ?.let { decodePolyMask(obj.maskPoly, it.first, it.second, probe) }

        else -> decodeMaskAny(obj.maskPngB64, obj.maskEncoding, probe)
    }

/**
 * Masque prêt à dessiner **à la taille de la frame** : décodage dans la grille du serveur,
 * puis agrandissement par plus proche voisin quand le codec a réduit la grille
 * (`maskScale < 1`). Les encodages pleine résolution (`png`, `bits`) sortent inchangés.
 */
fun decodeObjectGray(
    obj: SegObject,
    frameWidth: Int,
    frameHeight: Int,
    probe: DecodeProbe? = null,
): GrayMask? {
    val mask = decodeObjectMask(obj, frameWidth, frameHeight, probe) ?: return null
    return if (obj.maskScale < 1f) {
        probe.timed(DecodeStage.UP) { upscaleToFrame(mask, frameWidth, frameHeight) }
    } else {
        mask
    }
}

/** Grille réduite d'un objet (`mask_scale`), `null` si elle n'est pas exploitable. */
private fun compactGrid(obj: SegObject, frameWidth: Int, frameHeight: Int): Pair<Int, Int>? =
    maskGrid(frameWidth, frameHeight, obj.maskScale)