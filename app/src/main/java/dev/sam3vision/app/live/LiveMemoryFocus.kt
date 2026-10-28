package dev.sam3vision.app.live

import dev.sam3vision.app.LiveViewModel

/**
 * Instant demandé dans l'UI : « il y a N s » (0 = maintenant).
 *
 * Extension hors [LiveViewModel] pour que celui-ci reste sous la limite de
 * 500 lignes (même raison que `ProbeWiring.kt` côté sonde) : comportement
 * inchangé, appelant inchangé.
 */
fun LiveViewModel.setFocus(seconds: Int) = _state.update {
    it.copy(memory = it.memory.copy(focusSeconds = seconds.coerceAtLeast(0)))
}
