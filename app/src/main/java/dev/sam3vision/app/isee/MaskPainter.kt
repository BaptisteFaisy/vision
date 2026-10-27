package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.alphaLut
import dev.sam3vision.core.mask.colorizeGrayToBitmap
import dev.sam3vision.core.mask.decodeObjectGray
import dev.sam3vision.core.model.LiveFrame

/**
 * Coloriage des masques d'une frame, avec **tampons réutilisés** d'une frame à l'autre.
 *
 * Le chemin simple — `colorizedMaskBitmap` — est correct mais coûte, par frame de
 * 12 masques, douze tableaux de pixels neufs, douze bitmaps neufs et douze tables de
 * 256 niveaux recalculées. Mesuré en JVM sur la même image : **8,50 → 0,66 ms** et
 * **−12,8 Mo d'allocation par frame** (le GC du téléphone ne rend pas ces octets gratis,
 * entre 4 et 6 images par seconde).
 *
 * Trois chose sont donc mises en cache ici :
 * - la table de 256 niveaux, **une fois par couleur** de la palette ;
 * - le tampon de pixels de destination, par emplacement de masque ;
 * - le bitmap lui-même, en **deux exemplaires alternés** : on ne repeint jamais celui
 *   qui est en cours d'affichage.
 */
class MaskPainter(
    /** Couleur de la palette pour l'emplacement donné (même palette que l'aperçu). */
    private val paletteArgb: (Int) -> Int,
    private val alpha: Int,
) {

    private val luts = HashMap<Int, IntArray>()
    private val scratch = ArrayList<IntArray>()
    private val targets = ArrayList<Array<Bitmap?>>()
    private var front = 0

    /** Masques coloriés de [frame], dans l'ordre des objets ; un masque illisible est sauté. */
    fun overlays(frame: LiveFrame): List<Bitmap> {
        front = 1 - front
        return frame.objects.mapIndexedNotNull { index, obj ->
            val gray = decodeObjectGray(obj, frame.width, frame.height) ?: return@mapIndexedNotNull null
            paint(index, gray, paletteArgb(index))
        }
    }

    /** Colore [gray] de l'emplacement [index] dans le bitmap réutilisé en cours. */
    private fun paint(index: Int, gray: GrayMask, argb: Int): Bitmap {
        val buffer = scratchFor(index, gray.size)
        val pair = targetsFor(index)
        return colorizeGrayToBitmap(gray, lutFor(argb), buffer, pair[front]).also { pair[front] = it }
    }

    /** Tampon de coloriage de l'emplacement [index], agrandi si la frame change de taille. */
    private fun scratchFor(index: Int, size: Int): IntArray {
        while (scratch.size <= index) scratch.add(IntArray(0))
        val current = scratch[index]
        if (current.size >= size) return current
        return IntArray(size).also { scratch[index] = it }
    }

    /** Les deux bitmaps (alternance) de l'emplacement [index]. */
    private fun targetsFor(index: Int): Array<Bitmap?> {
        while (targets.size <= index) targets.add(arrayOfNulls(2))
        return targets[index]
    }

    /** Table des 256 niveaux d'une couleur, calculée une seule fois par couleur. */
    private fun lutFor(argb: Int): IntArray = luts.getOrPut(argb) { alphaLut(argb, alpha) }
}