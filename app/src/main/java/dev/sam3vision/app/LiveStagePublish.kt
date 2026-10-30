package dev.sam3vision.app

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.flow.update

/**
 * Publication des instantanés de chronologie : à part (même motif que
 * `LiveWiring.kt`), car `LiveViewModel.kt` est au plafond des 500 lignes.
 */

/** Instantané de la chronologie dans l'état : l'UI ne lit jamais les marques. */
internal fun LiveViewModel.publishStages(frame: LiveFrame?) =
    _state.update { it.copy(timings = chronology.snapshot(frame)) }

internal fun LiveViewModel.publishDrawnStages() =
    _state.update { current -> current.copy(timings = chronology.snapshot(current.timings)) }
