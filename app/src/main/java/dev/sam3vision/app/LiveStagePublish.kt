package dev.sam3vision.app

import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Publication des instantanés de chronologie : à part (même motif que
 * `LiveWiring.kt`), car `LiveViewModel.kt` est au plafond des 500 lignes.
 */

/**
 * Sous-flux du poste « pixel » : la ligne chrono le collecte seule, sans refaire
 * émettre l'état entier — une seule émission d'état par frame au lieu de deux.
 */
internal class PixelStages {
    private val flow = MutableStateFlow(FrameTimings())
    val stages: StateFlow<FrameTimings> = flow
    fun publish(snapshot: FrameTimings) {
        flow.value = snapshot
    }
    fun clear() {
        flow.value = FrameTimings()
    }
}

/** Instantané de la chronologie dans l'état : l'UI ne lit jamais les marques. */
internal fun LiveViewModel.publishStages(frame: LiveFrame?) {
    val snapshot = chronology.snapshot(frame)
    _state.update { it.copy(timings = snapshot) }
    pixel.publish(snapshot)
}

internal fun LiveViewModel.publishDrawnStages() {
    val snapshot = chronology.snapshot(_state.value.timings)
    pixel.publish(snapshot)
    LiveLatencyLog.frame(snapshot)
}

/** Boîtes prédites de la frame caméra, posées si la couche a changé. */
internal fun LiveViewModel.publishPrediction() {
    val layer = prediction.publishableLayer(System.currentTimeMillis(), lastKnownWidth, lastKnownHeight, _state.value.prediction) ?: return
    _state.update { it.copy(prediction = layer) }
}
