package dev.sam3vision.app.ihear

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.app.TRANSPORT_WS_KO
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameLabels
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.ApiError

/** Étiquettes de la frame, sans doublon (ensemble réutilisé, voir [FrameLabels]). */
internal fun probeLabels(labels: FrameLabels, frame: LiveFrame): List<String> =
    labels.of(frame.objects)

/** Libellé de transport : ce que le socket fait vraiment, pas ce qu'on voudrait. */
internal fun probeTransportLabel(active: LiveSession): String =
    if (active.socketOpen) TRANSPORT_WS else TRANSPORT_WS_KO

/** Distances de la frame (sténopé) ; aucun journal de suivi ici : mesure brute. */
internal fun probeReadings(meter: DistanceMeter, frame: LiveFrame): List<DistanceReading> {
    if (frame.width <= 0 || frame.height <= 0) return emptyList()
    val detections = frame.objects.toDetections(frame.width, frame.height)
    if (detections.isEmpty()) return emptyList()
    return meter.measure(frame.width, frame.height, detections)
}

/** Couche des pastilles ; `null` quand aucune distance n'est exploitable. */
internal fun probeDistanceLayer(frame: LiveFrame, readings: List<DistanceReading>): DistanceLayer? =
    if (readings.isEmpty() || frame.width <= 0 || frame.height <= 0) null
    else DistanceLayer(frame.width, frame.height, readings)

/**
 * Message d'échec lisible. Un délai dépassé au premier appel = conteneur Modal
 * endormi (~30 s de réveil) : on le dit, et la frame suivante réessaie d'elle-même.
 */
internal fun probeFailureText(error: Throwable): String =
    if (error is ApiError.Timeout) "réveil du GPU en cours… (nouvel essai à la frame suivante)"
    else error.message ?: "échec de l'appel SAM"
