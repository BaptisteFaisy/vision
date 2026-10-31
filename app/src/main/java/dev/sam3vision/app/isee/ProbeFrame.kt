package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.app.TRANSPORT_WS_KO
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameLabels
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.mask.toDetectionsWithExtents
import dev.sam3vision.core.model.Detection
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

/**
 * Détections SAM confirmées de la frame et leurs distances (sténopé) : une
 * seule conversion (les étendues masques coûtent un décodage par objet).
 * Score 1 : l'écran montre chaque masque, la voix dit chacun — mesuré ou non.
 */
internal fun detectionsAndReadingsOf(
    distances: DistanceMeter,
    frame: LiveFrame,
): Pair<List<Detection>, List<DistanceReading>> {
    if (frame.width <= 0 || frame.height <= 0) return emptyList<Detection>() to emptyList()
    val detections = frame.objects.toDetectionsWithExtents(frame.width, frame.height)
        .map { it.copy(score = 1f) }
    if (detections.isEmpty()) return detections to emptyList()
    return detections to distances.measure(frame.width, frame.height, detections)
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
