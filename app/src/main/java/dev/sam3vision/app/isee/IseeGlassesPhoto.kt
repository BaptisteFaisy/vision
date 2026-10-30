package dev.sam3vision.app.isee

import dev.sam3vision.core.glasses.CaptureAction
import java.io.File

/**
 * Une photo prise avec les lunettes, affichée sur l'accueil iSee (personne
 * accompagnée) : plein cadre, par-dessus la scène, fermée par retour ou tap.
 * Posée par [IseeViewModel.onGlassesPhoto], consommée par [GlassesPhotoLayer].
 */
data class IseeGlassesPhoto(
    val file: File,
    val action: CaptureAction,
    /** Compteur strictement croissant : deux photos ne partagent jamais leur identité. */
    val seq: Long,
)

/**
 * Vrai si [file] peut devenir (ou remplacer) la photo affichée : présent sur
 * disque et non vide, et pas le doublon de celle déjà à l'écran (même chemin).
 * Une photo *différente* remplace toujours la précédente (la dernière gagne).
 * Pure : testée en JVM.
 */
internal fun acceptGlassesPhoto(current: IseeGlassesPhoto?, file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    if (current != null && current.file.absolutePath == file.absolutePath) return false
    return true
}

/**
 * Ligne de statut posée à l'arrivée d'une photo lunettes : l'appui ASK attend
 * une question dictée (la photo en est déjà le contexte), PHOTO ne fait
 * qu'afficher. Pure : testée en JVM.
 */
internal fun glassesPhotoLabel(action: CaptureAction): String = when (action) {
    CaptureAction.ASK -> "Photo des lunettes : dictez votre question"
    CaptureAction.PHOTO -> "Photo des lunettes affichée"
    CaptureAction.LIVE -> "Photo des lunettes affichée"
}
