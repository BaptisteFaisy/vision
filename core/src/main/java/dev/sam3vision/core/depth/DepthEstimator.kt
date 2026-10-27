package dev.sam3vision.core.depth

/**
 * Moteur de profondeur embarqué : Depth Anything V2 Small (primaire, ONNX) ou
 * MiDaS small (repli, LiteRT), 100 % sur le téléphone — 0 GPU Modal.
 * [AUTO] prend DA2 si son asset est présent, sinon MiDaS, sinon coupé.
 */
enum class DepthBackend(
    /** Libellé des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court pour les pastilles et les journaux. */
    val code: String,
) {
    AUTO(label = "Automatique (DA2 puis MiDaS)", code = "AUTO"),
    DA2(label = "Depth Anything V2 Small", code = "DA2"),
    MIDAS(label = "MiDaS small (repli)", code = "MIDAS"),
    OFF(label = "Coupée", code = "OFF");

    companion object {
        /** Nom persisté → moteur ; valeur inconnue ou absente ⇒ [OFF] (sur, mesuré). */
        fun fromName(name: String?): DepthBackend =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
    }
}

/** Carte estimée + moteur réel + durée d'inférence (le banc de mesure la lit). */
data class DepthResult(
    val map: DepthMap,
    val backend: DepthBackend,
    val inferenceMs: Long,
)

/**
 * Estimation de profondeur sur une image ARGB ([IntArray], pas de `Bitmap` :
 * le prétraitement reste testable sur JVM, le redimensionnement vit côté appelant).
 */
interface DepthEstimator : AutoCloseable {
    /** Moteur réellement utilisé (un sélecteur AUTO annonce le moteur élu). */
    val backend: DepthBackend

    /** Carte de proximité de l'image ; échec = modèle absent ou inférence tombée. */
    suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult>
}

/** Moteur coupé : tout appel échoue proprement (l'appelant affiche « coupée »). */
object NoOpDepthEstimator : DepthEstimator {
    override val backend: DepthBackend = DepthBackend.OFF

    override suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult> =
        Result.failure(IllegalStateException("profondeur coupée"))

    override fun close() = Unit
}

/**
 * Image synthétique déterministe (dégradé + damier) pour le banc embarqué :
 * elle mesure la latence pure d'inférence sans asset photo ni caméra.
 */
fun syntheticGradientArgb(width: Int, height: Int): IntArray {
    require(width > 0 && height > 0) { "taille vide : ${width}x$height" }
    return IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val r = (x * 255 / width.coerceAtLeast(1)).coerceIn(0, 255)
        val g = (y * 255 / height.coerceAtLeast(1)).coerceIn(0, 255)
        val b = if ((x / 32 + y / 32) % 2 == 0) 255 else 0
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
