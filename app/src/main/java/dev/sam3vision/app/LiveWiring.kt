package dev.sam3vision.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos

/**
 * Câblage de la chronologie du direct, appelé par l'écran : isolé du ViewModel pour qu'il
 * reste sous la limite de 500 lignes — même motif que `ProbeWiring.kt` côté iSee.
 */

/**
 * Frame effectivement **dessinée** : dernier poste de la chronologie, posé par l'écran
 * après la composition (`withFrameNanos`). Tant que l'écran ne l'appelle pas, la ligne
 * s'arrête aux masques et le poste `pixel` affiche « — ».
 */
fun LiveViewModel.noteDrawn() {
    // L'instantané publié au retour réseau portait la frame en cours ; quand Compose
    // prévient enfin, le pipeline a déjà entamé la suivante. On republie donc depuis la
    // frame **dessinée** (postes téléphone) avec les chiffres serveur de l'instantané
    // d'avant — sinon le poste PIXEL tombe sur une frame qui n'est plus la bonne.
    // C'est ce qui affichait `pixel —` sur 12 relevés sur 12 le 2026-10-26.
    if (!chronology.noteDrawn()) return
    publishDrawnStages()
}

/** Dernière frame JPEG acceptée, `""` tant que le direct n'a rien gardé. */
fun LiveViewModel.latestFrameB64(): String = latestFrame.jpegB64

/**
 * Veilleur du premier pixel : une frame publiée n'est réellement **dessinée** qu'au frame
 * Compose suivant (les masques sont posés par l'`update` de l'AndroidView). Shared between
 * Direct et iSee — un seul endroit attend la frame et pose le dernier poste.
 */
@Composable
fun FirstPixelWatcher(trigger: Any?, active: Boolean, onDrawn: () -> Unit) {
    LaunchedEffect(trigger) {
        if (!active) return@LaunchedEffect
        withFrameNanos { }
        onDrawn()
    }
}
