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
 * L'offset du plan U est déduit : les [height] lignes du plan Y occupent
 * `yRowStride * height` octets, le chroma suit immédiatement.
 */
data class Yuv420Layout(
    val width: Int,
    val height: Int,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int = 1,
) {
    /** Largeur du plan de chroma (un échantillon pour deux pixels). */
    val chromaWidth: Int get() = (width + 1) / 2

    /** Hauteur du plan de chroma. */
    val chromaHeight: Int get() = (height + 1) / 2

    /** Le plan U commence juste après les lignes du plan Y. */
    val uOffset: Int get() = yRowStride * height

    /** Plan V : plan séparé (planaire) ou octet voisin de U (entrelacé). */
    val vOffset: Int
        get() = if (uvPixelStride >= 2) uOffset + 1 else uOffset + uvRowStride * chromaHeight

    /**
     * Taille minimale du buffer, padding de fin de ligne compris (les strides
     * sont uniformes : la dernière ligne est donc aussi paddée).
     */
    fun requiredBytes(): Int {
        val chroma = uvRowStride * chromaHeight
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
     * @return false si l'entrée est incohérente (dimensions nulles, strides trop
     *   courts, buffer trop petit, tableau de sortie trop court) ; dans ce cas
     *   [out] reste inchangé.
     */
    fun yuv420ToArgb(data: ByteArray, layout: Yuv420Layout, out: IntArray): Boolean {
        val width = layout.width
        val height = layout.height
        if (width <= 0 || height <= 0) return false
        if (layout.yRowStride < width) return false
        if (layout.uvPixelStride < 1) return false
        if (out.size < width * height) return false
        if (layout.requiredBytes() > data.size) return false
        fillArgb(data, layout, out)
        return true
    }

    /** Conversion pixel par pixel, sans allocation dans la boucle. */
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
            val uvBase = (row / 2) * uvStride
            for (col in 0 until width) {
                val y = data[yBase + col].toInt() and 0xFF
                val ci = uvBase + (col / 2) * uvStep
                val u = (data[uOffset + ci].toInt() and 0xFF) - 128
                val v = (data[vOffset + ci].toInt() and 0xFF) - 128
                out[o++] = argb(y, u, v)
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
