package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.live.TimingsText
import dev.sam3vision.core.mask.DecodeProbe
import dev.sam3vision.core.mask.DecodeSplit
import dev.sam3vision.core.mask.DecodeStage
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.alphaLut
import dev.sam3vision.core.mask.colorizeGrayToBitmap
import dev.sam3vision.core.mask.decodeObjectMask
import dev.sam3vision.core.mask.maskGridSize
import dev.sam3vision.core.mask.timed
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.SegObject

/**
 * Coloriage des masques d'une frame, avec **tampons réutilisés** d'une frame à l'autre.
 *
 * Le chemin simple — `colorizedMaskBitmap` — est correct mais coûte, par frame de
 * 12 masques, douze tableaux de pixels neufs, douze bitmaps neufs et douze tables de
 * 256 niveaux recalculées. Mesuré en JVM sur la même image : **8,50 → 0,66 ms** et
 * **−12,8 Mo d'allocation par frame** (le GC du téléphone ne rend pas ces octets gratis,
 * entre 4 et 6 images par seconde).
 *
 * Quatre choses sont donc mises en cache ici :
 * - la table de 256 niveaux, **une fois par couleur** (`alpha` est fixé au constructeur) ;
 * - le tampon de pixels de destination, par emplacement de masque ;
 * - le gris pleine frame ([lastGrays]), par emplacement : l'agrandissement n'alloue plus
 *   son tableau de 105 Ko par masque et par frame ;
 * - les tables d'axe du plus proche voisin, par (cible, source) : mêmes dimensions =
 *   zéro division, zéro allocation.
 *
 * Le bitmap lui-même va en **deux exemplaires alternés** : on ne repeint jamais celui
 * qui est en cours d'affichage.
 *
 * L'agrandissement et le coloriage sont **fusionnés en une passe**
 * ([upscaleAndColorizeInto]) : le niveau agrandi part dans le gris, sa couleur dans le
 * tampon de pixels, sans tableau intermédiaire ni seconde lecture de la frame. Contenu
 * identique à `upscaleToFrame` puis `colorizeGrayInto`, au pixel près (testé). La passe
 * est imputée à [DecodeStage.UP] : c'est la matérialisation grille → frame, le coloriage
 * y est gratuit — `paint` ne porte plus que le `poly` (sans gris) et le chemin historique.
 *
 * Le décodage est daté par poste (base64 → tampons, [DecodeSplit]) quand [measureDecode]
 * est vrai : une sonde neuve par frame (deux frames peuvent se décoder en concurrence),
 * jetée après l'instantané. Sonde à `null` quand le levier est éteint : le chemin de
 * décodage n'y voit qu'un test de nullité — coût nul, aucune lecture d'horloge.
 */
/**
 * Masque sauté au décodage : de quoi dire pourquoi (pas de bitmap).
 * `chars` = longueur base64 (caractères, ≈ octets × 4/3). Un `paint()`
 * nul (bitmap incréé) n'y figure pas : décodage seul ici.
 */
data class SkippedMask(val label: String, val encoding: String, val chars: Int)

/**
 * Résumé des masques sautés pour le log périodique : `label(encodage,caractères)`.
 * Pur (JVM) : le peintre ne fait que le coller à sa ligne.
 */
internal fun skippedSummary(skipped: List<SkippedMask>): String =
    skipped.take(6).joinToString(",") { "${it.label}(${it.encoding},${it.chars}c)" }

/**
 * Table d'un axe pour l'agrandissement au plus proche voisin : l'index source de chaque
 * pixel cible. Miroir exact de la table interne du cœur (`MaskUpscale`, privée) : même
 * formule au centre du pixel, même arithmétique — prouvé par le test au pixel près
 * (`MaskPainterTest`), pas par relecture. Mise en cache par le peintre.
 */
internal fun axisTable(target: Int, source: Int): IntArray =
    IntArray(target) { (((it + 0.5) * source) / target).toInt().coerceIn(0, source - 1) }

/**
 * [small] agrandi à `frameW × frameH` ET colorié ([lut]), en une passe : le niveau
 * agrandi part dans [gray] (`null` = coloriage seul, `poly`), sa couleur dans [scratch].
 * Contenu identique à `upscaleToFrame` puis `colorizeGrayInto`, sans le tableau gris
 * intermédiaire ni la seconde passe. Pur (JVM, sans bitmap).
 */
internal fun upscaleAndColorizeInto(
    small: GrayMask,
    frameW: Int,
    frameH: Int,
    lut: IntArray,
    xMap: IntArray,
    yMap: IntArray,
    gray: ByteArray?,
    scratch: IntArray,
) {
    val src = small.pixels
    val srcW = small.width
    for (y in 0 until frameH) {
        val sRow = yMap[y] * srcW
        val tRow = y * frameW
        for (x in 0 until frameW) {
            val level = src[sRow + xMap[x]]
            if (gray != null) gray[tRow + x] = level
            scratch[tRow + x] = lut[level.toInt() and 0xFF]
        }
    }
}

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

    private val luts = HashMap<Int, IntArray>()
    private val scratch = ArrayList<IntArray>()
    private val grayBufs = ArrayList<ByteArray>()
    private val targets = ArrayList<Array<Bitmap?>>()
    private val xAxes = HashMap<Long, IntArray>()
    private val yAxes = HashMap<Long, IntArray>()
    private var front = 0
    /** Paquets décodés depuis la création (pour le log périodique). */
    private var decodedFrames = 0

    /** Décomposition de la dernière frame : lue par la publication, après [noteDecoded]. */
    val lastDecode: DecodeSplit get() = lastSplit

    /**
     * Masques gris de la dernière frame, par emplacement d'objet (`null` = illisible) :
     * posés par [overlays], relus par la publication (distances, marches) qui ne
     * redécodent plus — un seul décodage par objet et par frame. Pleine frame sauf
     * `poly` sur grille réduite (gris ignoré en aval : étendue par sommets, marches
     * en repli bon marché) — jamais `null` pour un masque lisible.
     */
    @Volatile
    var lastGrays: List<GrayMask?> = emptyList()
        private set

    /** Masques sautés de la dernière frame ([overlays]), pour le log périodique. */
    @Volatile
    var lastSkipped: List<SkippedMask> = emptyList()
        private set

    /** Masques coloriés de [frame], dans l'ordre des objets ; un masque illisible est sauté. */
    fun overlays(frame: LiveFrame): List<Bitmap> {
        front = 1 - front
        val probe = if (measureDecode) DecodeProbe() else null
        // Un seul décodage par objet : les gris servent ici (coloriage) et à la
        // publication (distances, marches), via [lastGrays].
        val grays = ArrayList<GrayMask?>(frame.objects.size)
        val skipped = ArrayList<SkippedMask>()
        val out = ArrayList<Bitmap>(frame.objects.size)
        frame.objects.forEachIndexed { index, obj ->
            val small = decodeObjectMask(obj, frame.width, frame.height, probe)
            if (small == null) {
                grays += null
                skipped += SkippedMask(obj.label, obj.maskEncoding, obj.maskPngB64.length)
            } else if (wantsFullGray(obj, frame)) {
                grays += fusedFrame(index, small, frame, paletteArgb(index), probe)
                writeTarget(index, scratchFor(index, frame.width * frame.height), frame.width, frame.height)
                    ?.let(out::add)
            } else if (wantsUpscaledPaint(obj, frame)) {
                grays += small
                fusedPaint(index, small, frame, paletteArgb(index), probe)?.let(out::add)
            } else {
                grays += small
                paint(index, small, paletteArgb(index), probe)?.let(out::add)
            }
        }
        lastGrays = grays
        lastSkipped = skipped
        lastSplit = probe?.snapshot(totalMs = -1, masks = out.size) ?: DecodeSplit.EMPTY
        return out
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
        val skipped = lastSkipped
        val tail = if (skipped.isEmpty()) "" else " (sautés: ${skippedSummary(skipped)})"
        android.util.Log.i("sam3vision", "masques décodés: $masks en ${ms} ms$tail")
        if (lastSplit.known) android.util.Log.i(LiveLatencyLog.TAG, TimingsText.decsplit(lastSplit))
    }

    /**
     * Gris pleine frame voulu : grille réduite (`decodeObjectGray` agrandirait) sauf `poly`
     * (gris ignoré en aval). Mêmes gardiens que l'agrandissement du cœur : échelle < 1 et
     * cible valide, sinon le gris reste à sa grille (chemin historique).
     */
    private fun wantsFullGray(obj: SegObject, frame: LiveFrame): Boolean =
        obj.maskScale < 1f && obj.maskEncoding != MASK_ENCODING_POLY && frameDecent(frame)

    /** Coloriage agrandi sans gris : `poly` sur grille réduite (étendue par sommets en aval). */
    private fun wantsUpscaledPaint(obj: SegObject, frame: LiveFrame): Boolean =
        obj.maskScale < 1f && obj.maskEncoding == MASK_ENCODING_POLY && frameDecent(frame)

    /** Cible d'agrandissement valide : miroir du gardien de `upscaleNearest`. */
    private fun frameDecent(frame: LiveFrame): Boolean =
        maskGridSize(frame.width, frame.height) != null

    /**
     * Gris pleine frame de [small] + coloriage, en une passe ([DecodeStage.UP]) : le tampon
     * de pixels est rempli du même mouvement, [writeTarget] n'a plus qu'à l'écrire dans le
     * bitmap ping-pong. Contenu identique à `upscaleToFrame` puis `colorizeGrayInto`.
     */
    private fun fusedFrame(
        index: Int,
        small: GrayMask,
        frame: LiveFrame,
        argb: Int,
        probe: DecodeProbe?,
    ): GrayMask {
        val size = frame.width * frame.height
        val gray = grayBufFor(index, size)
        val scratch = scratchFor(index, size)
        probe.timed(DecodeStage.UP) {
            upscaleAndColorizeInto(
                small, frame.width, frame.height, lutFor(argb, alpha),
                axisFor(frame.width, small.width, xAxes), axisFor(frame.height, small.height, yAxes),
                gray, scratch,
            )
        }
        return GrayMask(frame.width, frame.height, gray)
    }

    /**
     * [small] agrandi et colorié vers le bitmap ping-pong ([DecodeStage.PAINT]), sans gris
     * pleine frame (`poly` : gris ignoré en aval). `null` = bitmap incréé, masque sauté.
     */
    private fun fusedPaint(
        index: Int,
        small: GrayMask,
        frame: LiveFrame,
        argb: Int,
        probe: DecodeProbe?,
    ): Bitmap? {
        val size = frame.width * frame.height
        val scratch = scratchFor(index, size)
        probe.timed(DecodeStage.PAINT) {
            upscaleAndColorizeInto(
                small, frame.width, frame.height, lutFor(argb, alpha),
                axisFor(frame.width, small.width, xAxes), axisFor(frame.height, small.height, yAxes),
                null, scratch,
            )
        }
        return writeTarget(index, scratch, frame.width, frame.height)
    }

    /**
     * Colore [gray] de l'emplacement [index] (`null` = bitmap increé, masque sauté).
     * Tous les labels sont peints opaques, décor compris (ordre user du 02/11 :
     * mur, trottoir et passage piéton visibles). La passe est datée
     * ([DecodeStage.PAINT]) quand la sonde est armée.
     */
    private fun paint(index: Int, gray: GrayMask, argb: Int, probe: DecodeProbe?): Bitmap? {
        val buffer = scratchFor(index, gray.size)
        val pair = targetsFor(index)
        return probe.timed(DecodeStage.PAINT) {
            colorizeGrayToBitmap(gray, lutFor(argb, alpha), buffer, pair[front])
        }.also { pair[front] = it }
    }

    /**
     * [scratch] écrit dans le bitmap ping-pong de l'emplacement [index], réutilisé s'il a
     * déjà la bonne taille. Même contrat que la moitié écriture de `colorizeGrayToBitmap` :
     * `null` si le bitmap ne se crée pas (l'appelant saute le masque, pas de crash).
     */
    private fun writeTarget(index: Int, scratch: IntArray, width: Int, height: Int): Bitmap? {
        val pair = targetsFor(index)
        var target = pair[front]
        if (target == null || target.isRecycled || target.width != width || target.height != height) {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) ?: return null
            pair[front] = target
        }
        target.setPixels(scratch, 0, width, 0, 0, width, height)
        return target
    }

    /** Tampon de coloriage de l'emplacement [index], agrandi si la frame change de taille. */
    private fun scratchFor(index: Int, size: Int): IntArray {
        while (scratch.size <= index) scratch.add(IntArray(0))
        val current = scratch[index]
        if (current.size >= size) return current
        return IntArray(size).also { scratch[index] = it }
    }

    /** Tampon du gris pleine frame de l'emplacement [index], agrandi comme [scratchFor]. */
    private fun grayBufFor(index: Int, size: Int): ByteArray {
        while (grayBufs.size <= index) grayBufs.add(ByteArray(0))
        val current = grayBufs[index]
        if (current.size >= size) return current
        return ByteArray(size).also { grayBufs[index] = it }
    }

    /** Les deux bitmaps (alternance) de l'emplacement [index]. */
    private fun targetsFor(index: Int): Array<Bitmap?> {
        while (targets.size <= index) targets.add(arrayOfNulls(2))
        return targets[index]
    }

    /**
     * Table d'axe (cible → source) en cache : mêmes dimensions = table rendue, sans
     * division ni allocation. Plafonné à 8 entrées par axe (dimensions stables en
     * pratique : frame × échelle) : au-delà, on purge et on recalcule.
     */
    private fun axisFor(target: Int, source: Int, cache: HashMap<Long, IntArray>): IntArray {
        if (cache.size >= MAX_AXIS_TABLES) cache.clear()
        val key = (target.toLong() shl 32) or (source.toLong() and 0xFFFFFFFFL)
        return cache.getOrPut(key) { axisTable(target, source) }
    }

    /** Table des 256 niveaux d'une couleur (`alpha` fixé au constructeur : une entrée suffit). */
    private fun lutFor(argb: Int, alpha: Int): IntArray =
        luts.getOrPut(argb) { alphaLut(argb, alpha) }

    private companion object {
        /** Tables d'axe gardées par axe : au-delà, purge (changement de régime). */
        const val MAX_AXIS_TABLES = 8
    }
}
