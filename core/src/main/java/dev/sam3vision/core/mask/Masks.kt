package dev.sam3vision.core.mask

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_PNG
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
fun decodeGrayMask(maskPngB64: String, probe: DecodeProbe? = null): GrayMask? {
    val bytes = base64Bytes(maskPngB64, probe) ?: return null
    return probe.timed(DecodeStage.IMG) { grayFromPngBytes(bytes) }
}

/** Corps de [decodeGrayMask], hors base64 : PNG → niveaux, repli ARGB compris. */
private fun grayFromPngBytes(bytes: ByteArray): GrayMask? {
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
fun decodeMaskAny(
    maskB64: String,
    encoding: String = MASK_ENCODING_PNG,
    probe: DecodeProbe? = null,
): GrayMask? = when (encoding) {
    MASK_ENCODING_BITS -> decodeBitsMask(maskB64, probe)
    MASK_ENCODING_NONE -> null
    else -> decodeGrayMask(maskB64, probe)
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

/** Masque gris déjà décodé → bitmap colorié (une allocation de pixels, aucune copie). */
fun colorizeGrayBitmap(mask: GrayMask, argb: Int, alpha: Int): Bitmap =
    Bitmap.createBitmap(colorizeGray(mask, argb, alpha), mask.width, mask.height, Bitmap.Config.ARGB_8888)

/**
 * Overlays coloriés d'une liste de masques (chemin froid : analyse photo,
 * snapshot du direct) : décodage selon [encoding], coloriage par [colorAt],
 * un masque illisible est sauté. Un seul endroit au lieu d'un
 * `mapIndexedNotNull` recopié par appelant (le chemin chaud garde son
 * peintre à tampons réutilisés côté app).
 */
fun colorizedOverlays(
    masks: List<String>,
    encoding: String,
    alpha: Int,
    colorAt: (Int) -> Int,
): List<Bitmap> = masks.mapIndexedNotNull { index, maskB64 ->
    colorizedMaskBitmap(maskB64, encoding, colorAt(index), alpha)
}

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

/**
 * Colore [mask] dans [scratch] (table [lut]) puis l'écrit dans [target], réutilisé s'il a
 * déjà la bonne taille : une frame ne coûte plus qu'une écriture de pixels, sans tableau
 * intermédiaire ni bitmap neuf. Renvoie le bitmap à afficher (jamais recyclé par l'appelant),
 * `null` si le bitmap ne se crée pas (mémoire : l'appelant saute le masque, pas de crash).
 */
fun colorizeGrayToBitmap(mask: GrayMask, lut: IntArray, scratch: IntArray, target: Bitmap?): Bitmap? {
    colorizeGrayInto(mask, lut, scratch)
    val bitmap = if (target != null && !target.isRecycled &&
        target.width == mask.width && target.height == mask.height
    ) {
        target
    } else {
        Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888) ?: return null
    }
    bitmap.setPixels(scratch, 0, mask.width, 0, 0, mask.width, mask.height)
    return bitmap
}
