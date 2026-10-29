package dev.sam3vision.app.voicenav

import dev.sam3vision.core.nav.progressOf
import dev.sam3vision.core.share.SharedTrip

/**
 * Trajet en cours → barre de progression du proche : destination, reste à
 * parcourir et avancement depuis le point de départ ([progressOf], 0 → 1).
 * `null` hors guidage : la barre disparaît, elle ne survit pas à l'arrêt.
 *
 * Partagé par l'accueil iSee et l'écran navigation (un seul implémente le
 * mapping) : le relais reçoit le même paquet quel que soit l'écran affiché.
 */
internal fun sharedTripOf(state: VoiceNavUiState): SharedTrip? {
    val guiding = state as? VoiceNavUiState.Guiding ?: return null
    val total = guiding.summary?.distanceMeters ?: 0L
    val remaining = guiding.update.remainingDistanceMeters
    return SharedTrip(
        destination = guiding.destination,
        totalMeters = total,
        remainingMeters = remaining,
        remainingSeconds = guiding.update.remainingDurationSeconds,
        progress = progressOf(remaining, total),
    )
}
