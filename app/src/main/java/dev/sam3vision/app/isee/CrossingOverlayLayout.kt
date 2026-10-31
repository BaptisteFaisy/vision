package dev.sam3vision.app.isee

import dev.sam3vision.app.cropTransform
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.traverse.TraversePhase

/**
 * Mise en place du **feu sur la vidéo** : le mode Traversée lit l'état du feu
 * piéton, mais jusqu'ici il ne le disait qu'en bas de carte ([crossingButtonLabel])
 * et par la voix. Ici on calcule de quoi le poser **sur l'image**, au même endroit
 * que les masques SAM : un cadre de la couleur de l'état, et une étiquette courte
 * au-dessus.
 *
 * Tout est pur (aucune dépendance Compose, hormis [CropTransform]) et testé en JVM
 * dans `CrossingOverlayLayoutTest` — la couche [CrossingOverlayLayer] ne fait que
 * dessiner ce que ces fonctions décident.
 */

/** Vert « traversez ». Mêmes teintes que la pastille de carte et que le guidage. */
const val CROSSING_ACCENT_GREEN = 0xFF2ECC71.toInt()

/** Rouge « attendez ». */
const val CROSSING_ACCENT_RED = 0xFFE74C3C.toInt()

/** Ambre « passage repéré, traversée proposée » (pastille d'offre, pas un état de feu). */
const val CROSSING_ACCENT_OFFER = 0xFFF1C40F.toInt()

/** Gris « mode Traversée coupé ». */
const val CROSSING_ACCENT_OFF = 0xFF808080.toInt()

/** Rectangle écran (px) du feu à marquer sur la vidéo. */
data class LightMarker(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    /** Largeur du cadre, en px écran. */
    val width: Float get() = right - left

    /** Hauteur du cadre, en px écran. */
    val height: Float get() = bottom - top
}

/**
 * Couleur du cadre et de l'étiquette pour [state] : binaire, rouge ou vert —
 * pas encore de lecture (`null`) attend comme un rouge (ordre user du 31/10).
 */
fun crossingAccent(active: Boolean, state: CrossingState?): Int {
    if (!active) return CROSSING_ACCENT_OFF
    return when (state) {
        CrossingState.Green -> CROSSING_ACCENT_GREEN
        CrossingState.Red, null -> CROSSING_ACCENT_RED
    }
}

/** Étiquette courte posée sur l'image (vide = rien à écrire). */
fun crossingTag(active: Boolean, state: CrossingState?): String {
    if (!active) return ""
    return when (state) {
        CrossingState.Green -> "GREEN"
        CrossingState.Red, null -> "RED"
    }
}

/**
 * Verdict de traversée posé sur la vidéo (vide = rien à écrire) : seul
 * [TraversePhase.TRAVERSER] (vert confirmé, maintenu, sans veto) autorise —
 * [TraversePhase.PRET] reste un « may not », prudent d'une frame.
 */
fun traverseTag(active: Boolean, phase: TraversePhase?): String {
    if (!active) return ""
    return when (phase) {
        TraversePhase.TRAVERSER -> "may cross"
        TraversePhase.PRET, TraversePhase.ATTENDRE, TraversePhase.ALERTE, null ->
            "may not cross"
    }
}

/**
 * Couleur du bandeau verdict : vert pour partir, rouge pour attendre (mêmes
 * teintes que le cadre du feu) ; gris mode coupé.
 */
fun traverseAccent(active: Boolean, phase: TraversePhase?): Int {
    if (!active) return CROSSING_ACCENT_OFF
    return when (phase) {
        TraversePhase.TRAVERSER -> CROSSING_ACCENT_GREEN
        TraversePhase.PRET, TraversePhase.ATTENDRE, TraversePhase.ALERTE, null ->
            CROSSING_ACCENT_RED
    }
}

/**
 * Cadre écran du feu, depuis sa boîte normalisée (`x1,y1,x2,y2` en 0..1, voir
 * `CrossingPrompts.selectLightBox`) et la taille de l'image analysée.
 *
 * La transformation est celle de l'aperçu ([cropTransform], `ContentScale.Crop`) —
 * la même que celle des pastilles de distance : sans elle, le cadre dérive dès que
 * le rapport de l'image diffère de celui de la carte. `null` = rien de dessinable
 * (pas de feu, boîte invalide, image dégénérée).
 */
fun lightMarker(
    box: List<Float>?,
    frameWidth: Int,
    frameHeight: Int,
    boxWidth: Float,
    boxHeight: Float,
): LightMarker? {
    if (box == null || box.size != 4) return null
    if (frameWidth <= 0 || frameHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return null
    val t = cropTransform(frameWidth, frameHeight, boxWidth, boxHeight)
    val left = t.screenX(box[0] * frameWidth).coerceIn(0f, boxWidth)
    val top = t.screenY(box[1] * frameHeight).coerceIn(0f, boxHeight)
    val right = t.screenX(box[2] * frameWidth).coerceIn(0f, boxWidth)
    val bottom = t.screenY(box[3] * frameHeight).coerceIn(0f, boxHeight)
    if (right - left < MIN_MARKER_PX || bottom - top < MIN_MARKER_PX) return null
    return LightMarker(left, top, right, bottom)
}

/**
 * Rectangle de l'étiquette : au-dessus du cadre du feu, et en dessous quand il n'y
 * a pas la place, puis ramenée dans l'écran. Un feu en haut de l'image ne doit pas
 * faire sortir son étiquette par le bord.
 */
fun tagRect(
    marker: LightMarker,
    tagWidth: Float,
    tagHeight: Float,
    boxWidth: Float,
    boxHeight: Float,
): LightMarker {
    val above = marker.top - tagHeight - TAG_GAP_PX
    val top = if (above >= 0f) above else (marker.bottom + TAG_GAP_PX).coerceAtMost(boxHeight - tagHeight)
    val left = marker.left.coerceIn(0f, (boxWidth - tagWidth).coerceAtLeast(0f))
    val clampedTop = top.coerceIn(0f, (boxHeight - tagHeight).coerceAtLeast(0f))
    return LightMarker(left, clampedTop, left + tagWidth, clampedTop + tagHeight)
}

/**
 * Rectangle du bandeau verdict : centré en haut de la vidéo, sous la puce
 * ([topMarginPx], fourni en px depuis des dp à l'appel). En bas, le panneau
 * debug le recouvrait (constaté sur téléphone : toujours affiché, devant).
 * Le verdict ne dépend d'aucune boîte SAM — même sans feu cadré, « may not
 * cross » reste affiché tant que le mode est actif.

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

/** Sous cette taille (px), un cadre ne désigne plus rien : on n'affiche rien. */
private const val MIN_MARKER_PX = 6f

/** Écart (px) entre le haut de la boîte et le bas de l'étiquette. */
private const val TAG_GAP_PX = 4f
