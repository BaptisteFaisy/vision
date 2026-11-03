package dev.sam3vision.app.glasses

// Types de frames partagés par le banc (`bench/`) et le collecteur de
// production ([GlassesFrameCollector]) : une seule définition du JPEG envoyé à
// l'API et de la conversion YUV, prouvée sur JVM par `YuvConverterTest`.

/**
 * Frame JPEG prête pour l'API (SAM / LLM).
 *
 * @property base64 JPEG encodé en base64, **sans** préfixe `data:image/jpeg;base64,`.
 * @property width largeur de l'image réellement encodée (après recadrage 16:9 et réduction).
 * @property height hauteur de l'image réellement encodée (après recadrage 16:9 et réduction).
 * @property capturedAtMs horodatage de capture, en millisecondes.
 */
data class JpegFrame(
    val base64: String,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
    /**
     * Garde W3 : vrai quand la frame brute s'écartait du 9:16
     * ([isGlassesAspectOff]) — l'aval mesure sur repli (`LensOrigin.FALLBACK`
     * côté live). Comparé sur les dimensions brutes, pas sur
     * `width`×`height` (recadré 4:3).
     */
    val aspectOff: Boolean = false,
)

/**
 * Disposition d'un buffer YUV 4:2:0 (plan Y, puis chroma).
 *
 * Reprend les conventions de l'API caméra Android : `rowStride` = octets entre
 * deux lignes d'un plan, `pixelStride` = octets entre deux échantillons voisins.
 *
 * - `uvPixelStride = 1` → I420 **planaire** : plan U puis plan V séparés ;
 * - `uvPixelStride = 2` → NV12 **semi-planaire** : U et V entrelacés, U en premier.
 *
 * L'offset du plan U est déduit : les [ySliceHeight] lignes du plan Y occupent
 * `yRowStride * ySliceHeight` octets, le chroma suit immédiatement. La tranche
 * vaut [height] quand le producteur ne padde pas (défaut) ; un décodeur
 * `MediaCodec` l'aligne souvent au-dessus (ex. 912 pour 896), et seules les
 * [height] premières lignes sont converties.
 */
data class Yuv420Layout(
    val width: Int,
    val height: Int,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int = 1,
    val ySliceHeight: Int = height,
) {
    /** Largeur du plan de chroma (un échantillon pour deux pixels). */
    val chromaWidth: Int get() = (width + 1) / 2

    /** Hauteur du plan de chroma. */
    val chromaHeight: Int get() = (height + 1) / 2

    /** Lignes de chroma stockées, padding de tranche compris. */
    val chromaSliceHeight: Int get() = (ySliceHeight + 1) / 2

    /** Le plan U commence juste après les lignes (paddées) du plan Y. */
    val uOffset: Int get() = yRowStride * ySliceHeight

    /** Plan V : plan séparé (planaire) ou octet voisin de U (entrelacé). */
    val vOffset: Int
        get() = if (uvPixelStride >= 2) uOffset + 1 else uOffset + uvRowStride * chromaSliceHeight

    /**
     * Taille minimale du buffer, paddings de ligne et de tranche compris (les
     * strides sont uniformes : la dernière ligne est donc aussi paddée).
     */
    fun requiredBytes(): Int {
        val chroma = uvRowStride * chromaSliceHeight
        return uOffset + if (uvPixelStride >= 2) chroma else 2 * chroma
    }
}

/**
 * Conversions YUV → ARGB pures (aucune dépendance Android) : c'est cette
 * couche qui est testée sur JVM, le `Bitmap` n'est créé qu'après coup.
 */
object YuvConverter {

    /**
     * Remplit [out] (format ARGB, alpha opaque) à partir d'un buffer YUV 4:2:0.
     *
     * @param data buffer contigu : plan Y puis plan(s) de chroma, selon [layout].
     * @param layout disposition (dimensions + strides) du buffer.
     * @param out tableau de destination, au moins `width * height` cases.
     * @return false si l'entrée est incohérente (dimensions nulles, strides ou
     *   tranche trop courts, buffer trop petit, tableau de sortie trop court) ;
     *   dans ce cas [out] reste inchangé.
     */
    fun yuv420ToArgb(data: ByteArray, layout: Yuv420Layout, out: IntArray): Boolean {
        val width = layout.width
        val height = layout.height
        if (width <= 0 || height <= 0) return false
        if (layout.yRowStride < width || layout.ySliceHeight < height) return false
        if (layout.uvPixelStride < 1) return false
        if (out.size < width * height) return false
        if (layout.requiredBytes() > data.size) return false
        fillArgb(data, layout, out)
        return true
    }

    /**
     * Remplit [out] en sous-échantillonnant d'un facteur 2 dans chaque axe
     * (un pixel sur quatre converti) : le pont réduit de toute façon à 320 px,
     * convertir le 720p plein (~921 kpx) avant de jeter les trois quarts est du
     * CPU jeté (~10-30 ms/frame). Mêmes gardes que [yuv420ToArgb], [out] fait
     * au moins [halfDimensions] cases.
     */
    fun yuv420ToArgbHalf(data: ByteArray, layout: Yuv420Layout, out: IntArray): Boolean {
        val width = layout.width
        val height = layout.height
        if (width <= 0 || height <= 0) return false
        if (layout.yRowStride < width || layout.ySliceHeight < height) return false
        if (layout.uvPixelStride < 1) return false
        val (halfW, halfH) = halfDimensions(width, height)
        if (out.size < halfW * halfH) return false
        if (layout.requiredBytes() > data.size) return false
        fillArgbHalf(data, layout, out, halfW, halfH)
        return true
    }

    /** Conversion d'un pixel sur quatre : additions seules dans la boucle interne. */
    private fun fillArgbHalf(data: ByteArray, layout: Yuv420Layout, out: IntArray, halfW: Int, halfH: Int) {
        val yStride = layout.yRowStride
        val uvStride = layout.uvRowStride
        val uvStep = layout.uvPixelStride
        val uOffset = layout.uOffset
        val vOffset = layout.vOffset
        var o = 0
        for (row in 0 until halfH) {
            // Pixel de sortie (row, col) = pixel source (2·row, 2·col) : le
            // chroma du couple (2·row, 2·col) est exactement (row, col).
            // Index incrémentaux (`yi`, `ci`) : plus de multiplication par pixel.
            var yi = (row * 2) * yStride
            val uRow = uOffset + row * uvStride
            val vRow = vOffset + row * uvStride
            var ci = 0
            repeat(halfW) {
                val y = data[yi].toInt() and 0xFF
                val u = (data[uRow + ci].toInt() and 0xFF) - 128
                val v = (data[vRow + ci].toInt() and 0xFF) - 128
                out[o++] = argb(y, u, v)
                yi += 2
                ci += uvStep
            }
        }
    }

    /**
     * Conversion pixel par pixel, sans allocation ni division dans la boucle :
     * deux pixels par cellule de chroma (une seule lecture U/V par paire),
     * index incrémental — la colonne impaire d'une largeur impaire prend seule
     * sa cellule, comme avant.
     */
    private fun fillArgb(data: ByteArray, layout: Yuv420Layout, out: IntArray) {
        val width = layout.width
        val height = layout.height
        val yStride = layout.yRowStride
        val uvStride = layout.uvRowStride
        val uvStep = layout.uvPixelStride
        val uOffset = layout.uOffset
        val vOffset = layout.vOffset
        var o = 0
        for (row in 0 until height) {
            val yBase = row * yStride
            val uRow = uOffset + (row / 2) * uvStride
            val vRow = vOffset + (row / 2) * uvStride
            var ci = 0
            for (pair in 0 until (width + 1) / 2) {
                val col = pair * 2
                val u = (data[uRow + ci].toInt() and 0xFF) - 128
                val v = (data[vRow + ci].toInt() and 0xFF) - 128
                out[o++] = argb(data[yBase + col].toInt() and 0xFF, u, v)
                if (col + 1 < width) out[o++] = argb(data[yBase + col + 1].toInt() and 0xFF, u, v)
                ci += uvStep
            }
        }
    }

    /** BT.601 en arithmétique entière (pas de flottant par pixel). */
    private fun argb(y: Int, u: Int, v: Int): Int {
        val r = (y + ((1436 * v) shr 10)).coerceIn(0, 255)
        val g = (y - ((352 * u) shr 10) - ((731 * v) shr 10)).coerceIn(0, 255)
        val b = (y + ((1815 * u) shr 10)).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Dimensions d'une conversion demi-résolution ([YuvConverter.yuv420ToArgbHalf]) :
 * moitié arrondie au supérieur (un impair garde son dernier pixel), jamais zéro.
 * Pure, testée en JVM.
 */
internal fun halfDimensions(width: Int, height: Int): Pair<Int, Int> =
    ((width + 1) / 2).coerceAtLeast(1) to ((height + 1) / 2).coerceAtLeast(1)

/**
 * Tampons réutilisés du pont lunettes : le décodeur H.265 sort ~1,4 Mo de YUV
 * par frame 720p, et la conversion demi-résolution ~0,9 Mo d'ARGB — alloués à
 * chaque frame, c'était ~25 Mo/s de garbage à 11 i/s, payés en pauses GC sur
 * le poste `capt` (p95 121 ms, max 489 ms en recette 13c). Ici les tableaux ne
 * repoussent que quand ils sont trop courts, jamais par frame. Usage séquentiel
 * seul (comme le `MediaCodec` qu'ils servent, non réentrant) : un décodeur = un
 * grattoir, et l'`ArgbImage` rendu est copié aussitôt (`createBitmap`, JPEG).
 */
internal class BufferScratch {
    private var bytes = ByteArray(0)
    private var ints = IntArray(0)

    /** Tampon d'octets d'au moins [size] cases (repousse amortie, ×2). */
    fun bytes(size: Int): ByteArray {
        if (bytes.size < size) bytes = ByteArray(maxOf(size, bytes.size * 2))
        return bytes
    }

    /** Tampon d'entiers d'au moins [size] cases (repousse amortie, ×2). */
    fun ints(size: Int): IntArray {
        if (ints.size < size) ints = IntArray(maxOf(size, ints.size * 2))
        return ints
    }
}

/** Alignements de ligne testés par [inferYuv420Layout] (pixels). */
private val ROW_ALIGNMENTS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256)

/**
 * Déduit la disposition YUV à partir de la **seule taille** du buffer.
 *
 * `VideoFrame` (SDK Meta) n'expose ni `rowStride`, ni `pixelStride`, ni format :
 * on cherche donc l'alignement de ligne (1 à 256 px) dont la taille de buffer
 * tombe juste. Deux conventions sont testées : le chroma aligné comme le plan Y
 * (`align(chromaWidth)`), puis le chroma à la moitié du pas du plan Y
 * (`yRowStride / 2`), convention la plus répandue côté matériel.
 *
 * @return la disposition déduite, ou null si le buffer est trop petit.
 */
fun inferYuv420Layout(byteCount: Int, width: Int, height: Int): Yuv420Layout? {
    if (byteCount <= 0 || width <= 0 || height <= 0) return null
    val chromaWidth = (width + 1) / 2
    for (align in ROW_ALIGNMENTS) {
        val layout = Yuv420Layout(width, height, alignUp(width, align), alignUp(chromaWidth, align), 1)
        if (layout.requiredBytes() == byteCount) return layout
    }
    for (align in ROW_ALIGNMENTS) {
        val yStride = alignUp(width, align)
        val layout = Yuv420Layout(width, height, yStride, (yStride / 2).coerceAtLeast(chromaWidth), 1)
        if (layout.requiredBytes() == byteCount) return layout
    }
    // Aucun alignement ne colle exactement : le buffer contient probablement des
    // octets de queue. On tente la disposition serrée (sans padding de ligne).
    // À VÉRIFIER : si le padding est réel mais inconnu, l'image sera décalée.
    val tight = Yuv420Layout(width, height, width, chromaWidth, 1)
    return if (tight.requiredBytes() <= byteCount) tight else null
}

/** Arrondi de [value] au multiple de [align] supérieur. */
private fun alignUp(value: Int, align: Int): Int = ((value + align - 1) / align) * align
