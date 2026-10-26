package dev.sam3vision.core.mask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import dev.sam3vision.core.model.SegObject
import java.nio.ByteBuffer

/**
 * Options de décodage des masques : le serveur envoie un PNG **gris 8 bits**
 * (`imaging.py::encode_mask_png_b64`), donc une seule composante par pixel.
 * `ALPHA_8` évite l'expansion ARGB_8888 que Skia ferait sinon (420 Ko au lieu de 105 Ko
 * par masque) et rend les niveaux tels quels, sans repasser par une luminance.
 */
private val GRAY_OPTIONS = BitmapFactory.Options().apply {
    inPreferredConfig = Bitmap.Config.ALPHA_8
    inScaled = false
}

/**
 * Décode un masque PNG gris 8 bits (base64) en Bitmap gris ARGB_8888.
 * `null` si le base64 ou le PNG est invalide. Chemin « froid » (snapshot du chat).
 */
fun decodeMaskPng(maskPngB64: String): Bitmap? {
    val gray = decodeGrayMask(maskPngB64) ?: return null
    return maskToBitmap(gray)
}

/** Overlay coloré du masque : même dimensions que [src], alpha modulé par la luminance. */
fun colorizeMask(src: Bitmap, argb: Int, alpha: Int): Bitmap {
    val width = src.width
    val height = src.height
    if (width <= 0 || height <= 0) return src
    val pixels = IntArray(width * height)
    src.getPixels(pixels, 0, width, 0, 0, width, height)
    val colorized = colorizeGray(grayFromArgb(pixels, width, height), argb, alpha)
    return Bitmap.createBitmap(colorized, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Masque gris (niveaux 0-255) depuis le PNG base64, sans passer par ARGB_8888.
 * Repli sur le décodage ARGB + luminance si le décodeur ne rend pas un bitmap ALPHA_8
 * (PNG palettisé/1 bit, masque d'un ancien backend) ou si les lignes sont rembourrées.
 */
fun decodeGrayMask(maskPngB64: String): GrayMask? {
    val bytes = base64Bytes(maskPngB64) ?: return null
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, GRAY_OPTIONS) ?: return null
    val width = bmp.width
    val height = bmp.height
    val levels = grayLevels(bmp)
    bmp.recycle()
    if (levels != null) return GrayMask(width, height, levels)
    return decodeGrayMaskArgb(bytes)
}

/**
 * Masque gris décodé **selon l'encodage annoncé par le backend** : `"bits"` (format `M1`,
 * zlib de bits, chemin pur Kotlin de [decodeBitsMask]), `"png"` (gris 8 bits, chemin
 * ALPHA_8) ou `"none"` (le serveur n'envoie aucun masque : le client dessine la boîte).
 * Encodage absent ou inconnu ⇒ PNG, donc le comportement d'avant le champ `mask_encoding`
 * — le backend déployé reste lisible.
 */
fun decodeMaskAny(maskB64: String, encoding: String = MASK_ENCODING_PNG): GrayMask? = when (encoding) {
    MASK_ENCODING_BITS -> decodeBitsMask(maskB64)
    MASK_ENCODING_NONE -> null
    else -> decodeGrayMask(maskB64)
}

/**
 * Masque colorié prêt à dessiner, depuis le base64 et son encodage : décodage selon
 * [encoding] puis coloriage par table ([alphaLut]), **sans** repasser par une luminance
 * (l'ancien chemin décolorait un masque déjà gris : −60 ms par frame sur le téléphone).
 * Une allocation de pixels par masque, aucun bitmap intermédiaire.
 * `null` si le masque est illisible ou absent (`"none"`) : l'appelant dessine alors la boîte.
 */
fun colorizedMaskBitmap(
    maskB64: String,
    encoding: String,
    argb: Int,
    alpha: Int,
): Bitmap? {
    val gray = decodeMaskAny(maskB64, encoding) ?: return null
    return colorizeGrayBitmap(gray, argb, alpha)
}

/**
 * Masque colorié d'un **objet du direct**, du base64 jusqu'au bitmap : décodage selon
 * l'encodage de l'objet ([decodeObjectGray], formats compacts `rle`/`poly` et
 * agrandissement depuis la grille réduite compris), puis coloriage par table.
 * `null` si le masque est illisible ou absent : la boîte reste dessinée.
 */
fun colorizedObjectBitmap(
    obj: SegObject,
    frameWidth: Int,
    frameHeight: Int,
    argb: Int,
    alpha: Int,
): Bitmap? {
    val gray = decodeObjectGray(obj, frameWidth, frameHeight) ?: return null
    return colorizeGrayBitmap(gray, argb, alpha)
}

/** Masque gris déjà décodé → bitmap colorié (une allocation de pixels, aucune copie). */
fun colorizeGrayBitmap(mask: GrayMask, argb: Int, alpha: Int): Bitmap =
    Bitmap.createBitmap(colorizeGray(mask, argb, alpha), mask.width, mask.height, Bitmap.Config.ARGB_8888)

/**
 * Niveaux 0-255 d'un bitmap ALPHA_8 (1 octet/pixel), `null` s'il faut passer par le repli.
 * `copyPixelsToBuffer` écrit directement dans le tableau : pas de copie intermédiaire.
 */
private fun grayLevels(bmp: Bitmap): ByteArray? {
    val width = bmp.width
    val height = bmp.height
    if (width <= 0 || height <= 0) return null
    // un décodeur qui rend du RGB n'a pas les niveaux : on le laisse au repli
    if (bmp.config != Bitmap.Config.ALPHA_8) return null
    // lignes rembourrées : l'extraction compacte serait à deviner, on préfère le repli
    if (bmp.rowBytes != width) return null
    val levels = ByteArray(width * height)
    bmp.copyPixelsToBuffer(ByteBuffer.wrap(levels))
    return levels
}

/** Repli : décodage ARGB puis luminance (masque non gris ou bitmap mal configuré). */
private fun decodeGrayMaskArgb(bytes: ByteArray): GrayMask? {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val width = bmp.width
    val height = bmp.height
    if (width <= 0 || height <= 0) {
        bmp.recycle()
        return null
    }
    val pixels = IntArray(width * height)
    bmp.getPixels(pixels, 0, width, 0, 0, width, height)
    bmp.recycle()
    return grayFromArgb(pixels, width, height)
}

/** Masque gris → Bitmap affichable (niveaux de gris opaques). */
fun maskToBitmap(mask: GrayMask): Bitmap {
    val pixels = IntArray(mask.size) { i ->
        val level = mask.pixels[i].toInt() and 0xFF
        (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }
    return Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
}

/**
 * Colore [mask] dans [scratch] (table [lut]) puis l'écrit dans [target], réutilisé s'il a
 * déjà la bonne taille : une frame ne coûte plus qu'une écriture de pixels, sans tableau
 * intermédiaire ni bitmap neuf. Renvoie le bitmap à afficher (jamais recyclé par l'appelant).
 */
fun colorizeGrayToBitmap(mask: GrayMask, lut: IntArray, scratch: IntArray, target: Bitmap?): Bitmap {
    colorizeGrayInto(mask, lut, scratch)
    val bitmap = if (target != null && !target.isRecycled &&
        target.width == mask.width && target.height == mask.height
    ) {
        target
    } else {
        Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    }
    bitmap.setPixels(scratch, 0, mask.width, 0, 0, mask.width, mask.height)
    return bitmap
}
