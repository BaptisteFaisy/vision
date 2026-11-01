package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.app.TRANSPORT_WS_KO
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameLabels
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.mask.toDetectionsWithExtents
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.flow.update

/**
 * Mise en forme d'une frame SAM : étiquettes, transport, distances, pastilles,
 * message d'échec.
 *
 * Extraits de [LiveProbe] pour qu'elle reste sous la limite de 500 lignes
 * (même raison que `ProbeWiring.kt` et `CrossingWiring.kt`) : logique
 * inchangée, dépendances passées en paramètres.
 *
 * À deux créneaux, les vols se chevauchent sur la chronologie unique de la sonde : les
 * marques du premier vol sont approximatives (le `beginFrame` suivant les remplace).
 * Seul le panneau debug est concerné — masques, distances et voix sont intacts.
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

/** Phrase d'armement : le réveil parle, plus de 30-50 s aveugles. */
internal const val WARM_SPOKEN = "Waking the server, up to a minute."

/** Phrase du réveil qui dépasse le délai froid (dite une fois par panne). */
internal const val COLD_TIMEOUT_SPOKEN = "Server still waking. Retrying."

/**
 * Envoi prioritaire : priorité armée par un refus, ou détections à l'écran —
 * ces frames sautent le garde temporel (jamais le créneau). L'urgence ne
 * passe plus par l'état (tuyau danger supprimé) : la voix de sécurité parle
 * direct, la priorité ne concerne que l'envoi.
 */
internal fun probePriority(state: LiveProbeState, armed: Boolean): Boolean =
    armed || state.objects > 0

/** Un refus de conversion (réserve pleine) : compté dans l'état, jamais silencieux. */
internal fun LiveProbe.noteSkipped() {
    _state.update { it.copy(skippedFrames = it.skippedFrames + 1) }
}

/**
 * Réveil qui dépasse le délai froid : dit une fois par panne (la phrase présente
 * prouve qu'on l'a déjà dit — pas de drapeau, pas de spam à chaque frame en échec).
 */
internal fun LiveProbe.sayColdTimeout() {
    val current = _state.value
    if (current.spoken == COLD_TIMEOUT_SPOKEN) return
    _state.update { it.copy(spoken = COLD_TIMEOUT_SPOKEN, spokenSeq = it.spokenSeq + 1) }
}

/**
 * Cap demandé à la voix (« va à gauche ») : le guidage recalcule le passage
 * vers ce cap dès la prochaine frame. L'annonceur repart de zéro pour que
 * la nouvelle consigne se dise sans attendre ; sans effet à cap identique.
 * Le cap retombe à tout droit à la prochaine session (voir [LiveProbe.begin]).
 */
internal fun LiveProbe.applySteering(cap: SteeringCap) {
    if (_state.value.steeringCap == cap) return
    voice.reset()
    _state.update { it.copy(steeringCap = cap) }
}
