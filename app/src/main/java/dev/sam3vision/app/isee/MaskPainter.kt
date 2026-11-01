package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.live.TimingsText
import dev.sam3vision.core.live.VeilPolicy
import dev.sam3vision.core.mask.DecodeProbe
import dev.sam3vision.core.mask.DecodeSplit
import dev.sam3vision.core.mask.DecodeStage
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.alphaLut
import dev.sam3vision.core.mask.colorizeGrayToBitmap
import dev.sam3vision.core.mask.decodeObjectGray
import dev.sam3vision.core.mask.timed
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
 *
 * Le décodage est daté par poste (base64 → tampons, [DecodeSplit]) quand [measureDecode]
 * est vrai : une sonde neuve par frame (deux frames peuvent se décoder en concurrence),
 * jetée après l'instantané. Sonde à `null` quand le levier est éteint : le chemin de
 * décodage n'y voit qu'un test de nullité — coût nul, aucune lecture d'horloge.
 */
class MaskPainter(
    /** Couleur de la palette pour l'emplacement donné (même palette que l'aperçu). */
    private val paletteArgb: (Int) -> Int,
    private val alpha: Int,
    measureDecode: Boolean = true,
) {

    /** Levier de la sonde de décodage : éteint, le décodage ne date rien. */
    @Volatile
    var measureDecode: Boolean = measureDecode

    /** Décomposition de la dernière frame décodée ([DecodeSplit.EMPTY] si éteint). */
    @Volatile
    private var lastSplit: DecodeSplit = DecodeSplit.EMPTY

    private val luts = HashMap<Pair<Int, Int>, IntArray>()
    private val scratch = ArrayList<IntArray>()
    private val targets = ArrayList<Array<Bitmap?>>()
    private var front = 0
    /** Paquets décodés depuis la création (pour le log périodique). */
    private var decodedFrames = 0

    /** Décomposition de la dernière frame : lue par la publication, après [noteDecoded]. */
    val lastDecode: DecodeSplit get() = lastSplit

    /** Masques coloriés de [frame], dans l'ordre des objets ; un masque illisible est sauté. */
    fun overlays(frame: LiveFrame): List<Bitmap> {
        front = 1 - front
        val probe = if (measureDecode) DecodeProbe() else null
        return frame.objects.mapIndexedNotNull { index, obj ->
            val gray = decodeObjectGray(obj, frame.width, frame.height, probe) ?: return@mapIndexedNotNull null
            paint(index, gray, paletteArgb(index), obj.label, probe)
        }.also { lastSplit = probe?.snapshot(totalMs = -1, masks = it.size) ?: DecodeSplit.EMPTY }
    }

    /**
     * Compte un paquet décodé : une ligne de log tous les [LOG_EVERY_FRAMES]
     * (ex-`LiveProbe`, plafond 500 lignes : le compteur vit avec le travail). Le temps mural
     * complète la décomposition, et la ligne `decsplit` part sous le tag [LiveLatencyLog.TAG]
     * : même dump `ISEE_LAT` que la sonde, jointure directe avec le poste `mask`.
     */
    fun noteDecoded(ms: Long, masks: Int) {
        decodedFrames += 1
        if (lastSplit.known) lastSplit = lastSplit.withTotal(ms)
        if (decodedFrames % LOG_EVERY_FRAMES != 0) return
        android.util.Log.i("sam3vision", "masques décodés: $masks en ${ms} ms")
        if (lastSplit.known) android.util.Log.i(LiveLatencyLog.TAG, TimingsText.decsplit(lastSplit))
    }

    /**
     * Colore [gray] de l'emplacement [index] (`null` = bitmap increé, masque sauté).
     * Un label de décor ([VeilPolicy]) est colorié en transparent : pastille et
     * voix inchangées, pas de voile à mi-écran. La passe est datée ([DecodeStage.PAINT])
     * quand la sonde est armée.
     */
    private fun paint(index: Int, gray: GrayMask, argb: Int, label: String, probe: DecodeProbe?): Bitmap? {
        val buffer = scratchFor(index, gray.size)
        val pair = targetsFor(index)
        val effAlpha = if (VeilPolicy.showsVeil(label)) alpha else 0
        return probe.timed(DecodeStage.PAINT) {
            colorizeGrayToBitmap(gray, lutFor(argb, effAlpha), buffer, pair[front])
        }.also { pair[front] = it }
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
