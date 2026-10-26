package dev.sam3vision.app.vm

import dev.sam3vision.app.UiState

/**
 * La seule façon dont les collaborateurs du [dev.sam3vision.app.AppViewModel] touchent
 * l'état de l'écran : une fonction de mise à jour, jamais une référence au
 * `MutableStateFlow`. Même convention que le ViewModel iSee (`StateUpdate`).
 */
typealias StateUpdate = ((UiState) -> UiState) -> Unit