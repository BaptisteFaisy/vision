package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Voix du direct téléphone : positions ([LivePositionVoice]) — les états
 * moteurs ne parlent qu'en Traversée (ordre user du 02/11). Groupées pour
 * le plafond 500 lignes de `LiveViewModel` — un champ, un reset, un appel
 * par frame.
 */
class LiveDirectAnnouncers(
    scope: CoroutineScope,
    state: MutableStateFlow<LiveUiState>,
    settings: () -> Settings,
    asking: () -> Boolean,
    speak: suspend (Settings, String) -> Unit,
) {
    private val positions = LivePositionVoice(scope, state, settings, asking, speak)

    /** Nouvelle séance : le guetteur oublie pistes et annonces. */
    fun reset() {
        positions.reset()
    }

    /** Reset positions seul, pour le câblage traversée (`setActive`/`deactivate`). */
    fun resetPositions() = positions.reset()

    /**
     * Annonce la frame : positions si [positionsActive] (direct, ou traversée
     * avec guidage conservé).
     */
    fun announce(
        frame: LiveFrame,
        width: Int,
        height: Int,
        nowMs: Long,
        readings: List<DistanceReading>,
        positionsActive: Boolean,
    ) {
        if (positionsActive) positions.announce(frame, width, height, nowMs, readings)
    }
}
