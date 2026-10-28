package dev.sam3vision.core.segment

/**
 * Moteur de segmentation embarquée : TwinLiteNet (LiteRT, primaire) ou coupé.
 * 100 % sur le téléphone — 0 GPU Modal : la couche rapide qui **complète**
 * SAM 3.1 (zone praticable immédiate + cadre exportable en prompt serveur).
 */
enum class SegmentBackend(
    /** Libellé des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court pour les pastilles et les journaux. */
    val code: String,
) {
    TWINLITE(label = "TwinLiteNet 640×360 (local)", code = "TWINLITE"),
    OFF(label = "Coupée", code = "OFF");

    companion object {
        /** Nom persisté → moteur ; valeur inconnue ou absente ⇒ [OFF] (sur). */
        fun fromName(name: String?): SegmentBackend =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
    }
}

/** Masque binaire (1 = avant-plan) à la résolution du modèle. */
data class SegmentMask(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
) {
    /** Part d'avant-plan 0..1 (la ligne d'état l'affiche). */
    fun coverage(): Float {
        if (bytes.isEmpty()) return 0f
        var count = 0
        for (b in bytes) if (b.toInt() != 0) count++
        return count.toFloat() / bytes.size
    }

    /**
     * Cadre englobant de l'avant-plan en pixels du masque, `null` si vide :
     * c'est ce que SAM 3.1 reçoit en prompt (voir [toPrompt]).
     */
    fun boundingBox(): MaskBox? {
        var x1 = width
        var y1 = height
        var x2 = -1
        var y2 = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bytes[y * width + x].toInt() == 0) continue
                if (x < x1) x1 = x
                if (y < y1) y1 = y
                if (x > x2) x2 = x
                if (y > y2) y2 = y
            }
        }
        if (x2 < 0) return null
        return MaskBox(x1, y1, x2, y2)
    }

    /**
     * Prompt boîte pour SAM 3.1 (repère 0..1000 de l'image d'origine) :
     * la segmentation locale désigne, le serveur segmente fin.
     */
    fun toPrompt(imageWidth: Int, imageHeight: Int): MaskPrompt? {
        val box = boundingBox() ?: return null
        val w = imageWidth.coerceAtLeast(1)
        val h = imageHeight.coerceAtLeast(1)
        // Masque → image d'origine : même écrasement que le prétraitement.
        fun nx(x: Int): Int = ((x.toFloat() / width) * 1000f).toInt().coerceIn(0, 1000)
        fun ny(y: Int): Int = ((y.toFloat() / height) * 1000f).toInt().coerceIn(0, 1000)
        return MaskPrompt(nx(box.x1), ny(box.y1), nx(box.x2), ny(box.y2))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentMask) return false
        return width == other.width && height == other.height && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** Cadre englobant en pixels du masque. */
data class MaskBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/** Boîte normalisée 0..1000, prête pour un prompt serveur. */
data class MaskPrompt(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/** Les deux masques TwinLiteNet + moteur réel + durée d'inférence. */
data class SegmentResult(
    val driveable: SegmentMask,
    val lanes: SegmentMask,
    val backend: SegmentBackend,
    val inferenceMs: Long,
)

/**
 * Segmentation de zone praticable sur une image ARGB ([IntArray], pas de
 * `Bitmap` : le prétraitement reste testable sur JVM, comme la profondeur).
 */
interface Segmenter : AutoCloseable {
    /** Moteur réellement utilisé (un sélecteur AUTO annonce le moteur élu). */
    val backend: SegmentBackend

    /** Délégué d'exécution effectif (GPU/CPU) : la mesure l'affiche. */
    val delegateName: String

    /** Masques de l'image ; échec = modèle absent ou inférence tombée. */
    suspend fun segment(argb: IntArray, width: Int, height: Int): Result<SegmentResult>
}

/** Moteur coupé : tout appel échoue proprement (l'appelant affiche « coupée »). */
object NoOpSegmenter : Segmenter {
    override val backend: SegmentBackend = SegmentBackend.OFF

    override val delegateName: String = "—"

    override suspend fun segment(argb: IntArray, width: Int, height: Int): Result<SegmentResult> =
        Result.failure(IllegalStateException("segmentation coupée"))

    override fun close() = Unit
}
