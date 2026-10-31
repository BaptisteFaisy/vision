package dev.sam3vision.core.depth

/**
 * Moteur de profondeur embarqué : Depth Anything V2 Small (ONNX), 100 % sur
 * le téléphone — 0 GPU Modal. Moteur unique depuis le 2026-10-29 (repli
 * MiDaS supprimé : DA2 est plus précis et ~1,6x plus rapide sur Redmi).
 */
enum class DepthBackend(
    /** Libellé des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court pour les pastilles et les journaux. */
    val code: String,
) {
    DA2(label = "Depth Anything V2 Small", code = "DA2"),
    OFF(label = "Off", code = "OFF");

    companion object {
        /**
         * Nom persisté → moteur. `AUTO`/`MIDAS` (réglages pré-29/10) migrent
         * vers [DA2] (la profondeur reste active) ; valeur inconnue ou
         * absente ⇒ [OFF] (sur, mesuré).
         */
        fun fromName(name: String?): DepthBackend {
            if (name.equals("AUTO", ignoreCase = true)) return DA2
            if (name.equals("MIDAS", ignoreCase = true)) return DA2
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
        }
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
    /** Moteur réellement utilisé (le backend élu par le sélecteur). */
    val backend: DepthBackend

    /** Carte de proximité de l'image ; échec = modèle absent ou inférence tombée. */
    suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult>
}

/** Moteur coupé : tout appel échoue proprement (l'appelant affiche « depth off »). */
object NoOpDepthEstimator : DepthEstimator {
    override val backend: DepthBackend = DepthBackend.OFF

    override suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult> =
        Result.failure(IllegalStateException("depth off"))

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
