package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.app.TRANSPORT_WS_KO
import dev.sam3vision.core.danger.DANGER_SMOOTHER_OFFSET
import dev.sam3vision.core.danger.DangerSnapshot
import dev.sam3vision.core.danger.dangerPlacedLabels
import dev.sam3vision.core.danger.toDetection
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameLabels
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.mask.toDetectionsWithExtents
import dev.sam3vision.core.model.LiveFrame

/**
 * Mise en forme d'une frame SAM : étiquettes, transport, distances, pastilles,
 * message d'échec.
 *
 * Extraits de [LiveProbe] pour qu'elle reste sous la limite de 500 lignes
 * (même raison que `ProbeWiring.kt` et `CrossingWiring.kt`) : logique
 * inchangée, dépendances passées en paramètres.
 */

/** Étiquettes de la frame, sans doublon (ensemble réutilisé, voir [FrameLabels]). */
internal fun labelsOf(labels: FrameLabels, frame: LiveFrame): List<String> = labels.of(frame.objects)

/** Libellé de transport : ce que le socket fait vraiment, pas ce qu'on voudrait. */
internal fun transportLabel(active: LiveSession): String =
    if (active.socketOpen) TRANSPORT_WS else TRANSPORT_WS_KO

/** Distances de la frame (sténopé) ; aucun journal de suivi ici : mesure brute. */
internal fun readingsOf(distances: DistanceMeter, frame: LiveFrame): List<DistanceReading> {
    if (frame.width <= 0 || frame.height <= 0) return emptyList()
    val detections = frame.objects.toDetectionsWithExtents(frame.width, frame.height)
    if (detections.isEmpty()) return emptyList()
    return distances.measure(frame.width, frame.height, detections)
}

/**
 * Labels danger (YOLO) pour la voix : pistes mesurées au sténopé (personne
 * 1,70 m) et lissées par id décalé. Vide sans piste, sans dimensions, ou
 * distances coupées — la voix existante parle alors comme avant.
 */
internal fun dangerPlaced(distances: DistanceMeter, snapshot: DangerSnapshot): List<PlacedLabel> {
    if (snapshot.tracks.isEmpty() || snapshot.width <= 0 || snapshot.height <= 0) return emptyList()
    val detections = snapshot.tracks.map { it.toDetection(snapshot.width, snapshot.height) }
    val ids = snapshot.tracks.map { it.id + DANGER_SMOOTHER_OFFSET }
    val readings = distances.measure(snapshot.width, snapshot.height, detections, ids)
    if (readings.isEmpty()) return emptyList()
    return dangerPlacedLabels(snapshot.tracks, readings)
}

/** Couche des pastilles ; `null` quand aucune distance n'est exploitable. */
internal fun distanceLayerOf(frame: LiveFrame, readings: List<DistanceReading>): DistanceLayer? =
    if (readings.isEmpty() || frame.width <= 0 || frame.height <= 0) null
    else DistanceLayer(frame.width, frame.height, readings)

/** Plafond d'objets quand les Réglages n'en imposent pas (valeur du Direct). */
internal const val DEFAULT_MAX_OBJECTS = 12

/** Un log de décodage toutes N frames décodées. */
internal const val LOG_EVERY_FRAMES = 20

/** Délai normal d'un appel du direct (le réveil du conteneur passe par [COLD_TIMEOUT_SECONDS]). */
internal const val DEFAULT_TIMEOUT_SECONDS = 10L

/** Délai du premier appel : couvre le démarrage d'un conteneur Modal (30-50 s). */
internal const val COLD_TIMEOUT_SECONDS = 60L
