package dev.sam3vision.app.detect

import dev.sam3vision.core.stairs.StairSignal
import java.util.Locale

/**
 * Cadre « escalier » pour la surcouche NanoDet : bande plein-largeur sur la
 * ROI (bas 60 %, comme l'aplat magenta de la démo MIT `draw_stair_warning`) +
 * étiquette `ESCALIER <confiance>`. Pur Kotlin, testé en JVM ; le dessin
 * Compose vit dans `NanoDetScreen` (comme `DetectionOverlay`).
 */
data class StairFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
)

/**
 * Cadre d'affichage du [signal] sur une image large de [width] pixels, `null`
 * quand rien n'est détecté (la surcouche ne peint rien, comme sans boîtes).
 */
fun stairFrameFor(signal: StairSignal, width: Int): StairFrame? {
    if (!signal.detected || width <= 0) return null
    return StairFrame(
        left = 0f,
        top = signal.roiTop.toFloat(),
        right = width.toFloat(),
        bottom = signal.roiBottom.toFloat(),
        label = String.format(Locale.US, "ESCALIER %.2f", signal.confidence),
    )
}
