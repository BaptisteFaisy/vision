package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.core.live.VeilPolicy
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
 * - la table de 256 niveaux, **une fois par (couleur, alpha)** ;
 * - le tampon de pixels de destination, par emplacement de masque ;
 * - le bitmap lui-même, en **deux exemplaires alternés** : on ne repeint jamais celui
 *   qui est en cours d'affichage.
 */
class MaskPainter(
    /** Couleur de la palette pour l'emplacement donné (même palette que l'aperçu). */
    private val paletteArgb: (Int) -> Int,
    private val alpha: Int,
) {

    private val luts = HashMap<Pair<Int, Int>, IntArray>()
    private val scratch = ArrayList<IntArray>()
    private val targets = ArrayList<Array<Bitmap?>>()
    private var front = 0
    /** Paquets décodés depuis la création (pour le log périodique). */
    private var decodedFrames = 0

    /** Masques coloriés de [frame], dans l'ordre des objets ; un masque illisible est sauté. */
    fun overlays(frame: LiveFrame): List<Bitmap> {
        front = 1 - front
        return frame.objects.mapIndexedNotNull { index, obj ->
            val gray = decodeObjectGray(obj, frame.width, frame.height) ?: return@mapIndexedNotNull null
            paint(index, gray, paletteArgb(index), obj.label)
        }
    }

    /**
     * Compte un paquet décodé : une ligne de log tous les [LOG_EVERY_FRAMES]
     * (ex-`LiveProbe`, plafond 500 lignes : le compteur vit avec le travail).
     */
    fun noteDecoded(ms: Long, masks: Int) {
        decodedFrames += 1
        if (decodedFrames % LOG_EVERY_FRAMES != 0) return
        android.util.Log.i("sam3vision", "masques décodés: $masks en ${ms} ms")
    }

    /**
     * Colore [gray] de l'emplacement [index] (`null` = bitmap increé, masque sauté).
     * Un label de décor ([VeilPolicy]) est colorié en transparent : pastille et
     * voix inchangées, pas de voile à mi-écran.
     */
    private fun paint(index: Int, gray: GrayMask, argb: Int, label: String): Bitmap? {
        val buffer = scratchFor(index, gray.size)
        val pair = targetsFor(index)
        val effAlpha = if (VeilPolicy.showsVeil(label)) alpha else 0
        return colorizeGrayToBitmap(gray, lutFor(argb, effAlpha), buffer, pair[front]).also { pair[front] = it }
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

    /** Table des 256 niveaux d'un (couleur, alpha), calculée une seule fois par couple. */
    private fun lutFor(argb: Int, alpha: Int): IntArray =
        luts.getOrPut(argb to alpha) { alphaLut(argb, alpha) }
}
