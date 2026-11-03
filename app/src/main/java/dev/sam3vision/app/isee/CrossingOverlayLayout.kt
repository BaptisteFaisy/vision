package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState

/**
 * Couleurs et géométrie du mode Traversée : la pastille du chip
 * ([crossingAccent], même teinte que le libellé [crossingButtonLabel]) et le
 * bandeau centré du scan ([verdictRect]). Le cadre rouge/vert du feu sur la
 * vidéo n'est plus posé (ordre user du 03/11).
 *
 * Tout est pur et testé en JVM dans `CrossingOverlayLayoutTest`.
 */

/** Vert « traversez ». Mêmes teintes que la pastille de carte et que le guidage. */
const val CROSSING_ACCENT_GREEN = 0xFF2ECC71.toInt()

/** Rouge « attendez ». */
const val CROSSING_ACCENT_RED = 0xFFE74C3C.toInt()

/** Ambre « en cours » : bandeau de scan en attente, cap bouché. */
const val CROSSING_ACCENT_OFFER = 0xFFF1C40F.toInt()

/** Gris « mode Traversée coupé ». */
const val CROSSING_ACCENT_OFF = 0xFF808080.toInt()

/** Rectangle écran (px) d'un bandeau posé sur la vidéo. */
data class LightMarker(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    /** Largeur du cadre, en px écran. */
    val width: Float get() = right - left

    /** Hauteur du cadre, en px écran. */
    val height: Float get() = bottom - top
}

/**
 * Couleur de la pastille du chip pour [state] : vert pour traverser,
 * rouge pour attendre — pas encore de lecture (`null`) attend comme un
 * rouge (fail-safe, reel audit 31/10).
 */
fun crossingAccent(active: Boolean, state: CrossingState?): Int {
    if (!active) return CROSSING_ACCENT_OFF
    return when (state) {
        CrossingState.Green -> CROSSING_ACCENT_GREEN
        CrossingState.Red, null -> CROSSING_ACCENT_RED
    }
}

/**
 * Rectangle d'un bandeau centré en haut de la vidéo, sous la puce
 * ([topMarginPx], fourni en px depuis des dp à l'appel). En bas, le panneau
 * debug le recouvrait (constaté sur téléphone : toujours affiché, devant).
 * Le bandeau ne dépend d'aucune boîte SAM — même sans feu cadré, le scan
 * reste affiché tant que le mode est actif.
 */
fun verdictRect(
    tagWidth: Float,
    tagHeight: Float,
    boxWidth: Float,
    boxHeight: Float,
    topMarginPx: Float,
): LightMarker {
    val left = ((boxWidth - tagWidth) / 2f).coerceIn(0f, (boxWidth - tagWidth).coerceAtLeast(0f))
    val top = topMarginPx.coerceIn(0f, (boxHeight - tagHeight).coerceAtLeast(0f))
    return LightMarker(left, top, left + tagWidth, top + tagHeight)
}
