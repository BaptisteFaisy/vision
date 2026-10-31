package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.isee.LiveVehicleVoice
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Voix du direct téléphone : positions ([LivePositionVoice]) + états moteurs
 * ([LiveVehicleVoice], doux, jamais de stop hors traversée), groupées pour le
 * plafond 500 lignes de `LiveViewModel` — un champ, un reset, un appel par frame.
 */
class LiveDirectAnnouncers(
    scope: CoroutineScope,
    state: MutableStateFlow<LiveUiState>,
    settings: () -> Settings,
    traverseActive: () -> Boolean,
    asking: () -> Boolean,
    speak: suspend (Settings, String) -> Unit,
) {
    private val positions = LivePositionVoice(scope, state, settings, asking, speak)
    private val vehicles = LiveVehicleVoice(scope, state, settings, traverseActive, asking, speak)

    /** Nouvelle séance : les deux guetteurs oublient pistes et annonces. */
    fun reset() {
        positions.reset()
        vehicles.reset()
    }

    /** Reset positions seul, pour le câblage traversée (`setActive`/`deactivate`). */
    fun resetPositions() = positions.reset()

    /**
     * Annonce la frame : positions si [positionsActive] (direct, ou traversée
     * avec guidage conservé), puis états moteurs (coupés en traversée).
     */
    fun announce(
        frame: LiveFrame,
        width: Int,
        height: Int,
        nowMs: Long,
        readings: List<DistanceReading>,
        detections: List<Detection>,
        positionsActive: Boolean,
    ) {
        if (positionsActive) positions.announce(frame, width, height, nowMs, readings)
        vehicles.announce(detections, readings, nowMs)
    }
}
