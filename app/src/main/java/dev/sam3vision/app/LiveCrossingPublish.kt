package dev.sam3vision.app

import dev.sam3vision.app.isee.CrossingLight

/**
 * Pose le feu du mode Traversée dans l'état du direct, pour que l'overlay le
 * dessine sur la même vidéo que les masques.
 *
 * Sorti de [LiveViewModel] pour la laisser sous la limite de 500 lignes (même
 * raison que `LiveWiring.kt` et `ProbeWiring.kt`). Mode coupé ou feu jamais lu :
 * rien n'est publié, donc rien n'est dessiné — un cadre sans boîte mentirait sur
 * la position du feu.
 */
internal fun LiveUiState.withCrossing(active: Boolean, light: CrossingLight?): LiveUiState {
    if (!active || light == null) return this
    return copy(crossing = light.state, crossingBox = light.box)
}
