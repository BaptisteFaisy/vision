package dev.sam3vision.core.detect

/**
 * Moteur de détection embarquée : NanoDet-Plus 320 (ONNX, primaire) ou coupé.
 * 100 % sur le téléphone — 0 GPU Modal : la couche rapide qui **complète**
 * SAM 3.1 (boîtes immédiates + régions d'intérêt pour les prompts serveur).
 */
enum class DetectBackend(
    /** Libellé des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court pour les pastilles et les journaux. */
    val code: String,
) {
    NANODET(label = "NanoDet-Plus 320 (local)", code = "NANODET"),
    OFF(label = "Coupée", code = "OFF");

    companion object {
        /** Nom persisté → moteur ; valeur inconnue ou absente ⇒ [OFF] (sur). */
        fun fromName(name: String?): DetectBackend =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
    }
}

/** Boîte en pixels de l'image d'origine + classe COCO + confiance. */
data class DetectBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val label: Int,
    val labelName: String,
    val score: Float,
) {
    /**
     * Prompt boîte pour SAM 3.1 (repère 0..1000 de l'image d'origine) : la
     * détection locale désigne, le serveur segmente — sans nouvel envoi.
     */
    fun toSamPrompt(imageWidth: Int, imageHeight: Int): SamBoxPrompt {
        val w = imageWidth.coerceAtLeast(1)
        val h = imageHeight.coerceAtLeast(1)
        return SamBoxPrompt(
            x1 = ((x1 / w) * 1000f).toInt().coerceIn(0, 1000),
            y1 = ((y1 / h) * 1000f).toInt().coerceIn(0, 1000),
            x2 = ((x2 / w) * 1000f).toInt().coerceIn(0, 1000),
            y2 = ((y2 / h) * 1000f).toInt().coerceIn(0, 1000),
        )
    }
}

/** Boîte normalisée 0..1000, prête pour un prompt serveur. */
data class SamBoxPrompt(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/** Boîtes estimées + moteur réel + durée d'inférence (le banc la lit). */
data class DetectResult(
    val boxes: List<DetectBox>,
    val backend: DetectBackend,
    val inferenceMs: Long,
)

/**
 * Détection d'objets sur une image ARGB ([IntArray], pas de `Bitmap` :
 * le prétraitement reste testable sur JVM, comme la profondeur).
 */
interface Detector : AutoCloseable {
    /** Moteur réellement utilisé (un sélecteur AUTO annonce le moteur élu). */
    val backend: DetectBackend

    /** Boîtes de l'image ; échec = modèle absent ou inférence tombée. */
    suspend fun detect(argb: IntArray, width: Int, height: Int): Result<DetectResult>
}

/** Moteur coupé : tout appel échoue proprement (l'appelant affiche « coupée »). */
object NoOpDetector : Detector {
    override val backend: DetectBackend = DetectBackend.OFF

    override suspend fun detect(argb: IntArray, width: Int, height: Int): Result<DetectResult> =
        Result.failure(IllegalStateException("détection coupée"))

    override fun close() = Unit
}

/**
 * 80 classes COCO de NanoDet-Plus, dans l'ordre du modèle (orthographe amont,
 * espaces — `labels.txt` du convertisseur dit pareil avec des `_`).
 */
val COCO_LABELS: List<String> = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
    "truck", "boat", "traffic light", "fire hydrant", "stop sign",
    "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag",
    "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
    "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon",
    "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
    "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
    "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
    "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
)
