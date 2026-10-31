package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveTimings
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.placedLabels
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.stairs.stairsMaskPlaneOf
import kotlinx.coroutines.flow.update

/**
 * Chiffres d'une frame, calculés entre les deux écritures de [publish] : tout
 * ce qui n'est pas le pixel (distances, phrase, Traversée). Regroupés ici pour
 * que le découpage pixels/chiffres se teste écriture par écriture.
 */
internal data class ProbeRest(
    val rawDetections: List<Detection>,
    val readings: List<DistanceReading>,
    val placed: List<PlacedLabel>,
    val cross: CrossingPatch?,
    val phrase: String?,
)

/**
 * Publication d'une frame analysée, en **deux écritures** (pixels d'abord).
 *
 * L'écriture #1 ([publishPixels]) porte les masques recolorés, les chiffres et
 * la chronologie : l'écran compose dès que les pixels sont prêts. L'écriture #2
 * ([publishRest]) porte les distances, les détections, la phrase et le mode
 * Traversée, calculés entre les deux (~4–12 ms hors Traversée, décodage JPEG du
 * feu exclu en Traversée).
 *
 * [LiveProbeState.frames] n'avance qu'en #2 : les guetteurs voix (`observe*`,
 * `ProbeWiring.kt`) se déclenchent sur `frames` et lisent les détections — les
 * avancer en #1 les ferait parler sur des chiffres périmés d'une frame. Le
 * poste PIXEL, lui, est accroché à [LiveProbeState.pixelSeq] (écriture #1) via
 * `FirstPixelWatcher` : c'est la mesure voulue, sans attendre la voix.
 *
 * Ici et pas dans `LiveProbe.kt` : ce fichier est au plafond des 500 lignes,
 * et `publish` n'a besoin que de membres `internal` de la sonde.
 */
internal suspend fun LiveProbe.publish(
    active: LiveSession, frame: LiveFrame, jpegB64: String, at: Long, shown: LiveTimings,
) {
    publishPixels(active, frame, shown)
    publishRest(active, frame, computeRest(frame, jpegB64, at))
}

/**
 * Écriture #1 : masques, chiffres, chronologie. `frames` n'avance pas (voir
 * [publish]) ; `pixelSeq` avance et déclenche le poste PIXEL à la composition.
 */
internal suspend fun LiveProbe.publishPixels(
    active: LiveSession, frame: LiveFrame, shown: LiveTimings,
) {
    val overlays = overlaysFor(frame, shown, background, painter, chronology)
    _state.update { current ->
        current.copy(
            masks = overlays,
            labels = labelsOf(labels, frame),
            objects = frame.objects.size,
            latencyMs = active.stats.averageMs,
            p95Ms = active.stats.p95Ms,
            serverMs = frame.elapsedMs,
            timings = chronology.snapshot(shown, frame),
            width = frame.width,
            height = frame.height,
            fps = rate.rate,
            pixelSeq = current.pixelSeq + 1,
            transport = transportLabel(active),
            error = null,
        )
    }
}

/**
 * Chiffres d'une frame ([ProbeRest]) + effets de bord (impulsion, compteur de
 * marches) : le travail qui sort du chemin pixels, entre les deux écritures.
 */
internal fun LiveProbe.computeRest(frame: LiveFrame, jpegB64: String, at: Long): ProbeRest {
    val (rawDetections, pinhole) = detectionsAndReadingsOf(distances, frame)
    val readings = meters.readingsFor(frame, pinhole)
    val placed = placedLabels(frame, readings)
    val cross = crossingPatchOf(
        crossing, traverseVeto, _state.value.crossingActive, frame, distances.lensSpec, jpegB64, at, speechLanguage,
        onCrop = cropLogger?.tap(), noLightGate = noLightGate,
    )
    val scanTrigger = scanTriggerFor(appContext, frame.objects, frame.width, frame.height, readings, distances.pose, distances.lensSpec)
    val phrase = phraseOf(cross, placed, readings, rawDetections, frame, at, speechLanguage,
        distances.lensSpec.widthFovDeg(frame.width, frame.height), scanTrigger)
    feedCrossingLateral(_state.value.crossingActive, frame.objects, frame.width, frame.height, readings, distances.pose, distances.lensSpec, at, speechLanguage)
    pulse.feed(at, voice.lastDecision, placed); meters.noteFrame(frame.width, frame.height)
    // Compteur de marches : le masque exact borne les frames suivantes (repli boîte sinon).
    StairCannyCount.setMaskPlane(stairsMaskPlaneOf(frame.objects, frame.width, frame.height))
    return ProbeRest(rawDetections, readings, placed, cross, phrase)
}

/**
 * Écriture #2 : `frames` avance avec les chiffres et la voix — les guetteurs
 * voient une frame complète, jamais des pixels sans leurs mesures.
 */
internal fun LiveProbe.publishRest(active: LiveSession, frame: LiveFrame, rest: ProbeRest) {
    _state.update { current ->
        current.copy(
            frames = current.frames + 1,
            distanceLayer = distanceLayerOf(frame, rest.readings),
            samDetections = rest.rawDetections,
            spoken = rest.phrase ?: current.spoken,
            spokenSeq = if (rest.phrase != null) current.spokenSeq + 1 else current.spokenSeq,
            spokenUrgent = if (rest.phrase != null) spokenUrgentOf(rest.phrase) else current.spokenUrgent,
            steering = steeringCueOf(),
            samPassage = frame.passage,
            detour = if (_state.value.crossingActive) false else voice.detour,
            crossing = rest.cross?.state ?: current.crossing, crossingBox = rest.cross?.box,
            traversePhase = rest.cross?.phase,
            scanLines = CrosswalkScanWatch.display(speechLanguage),
        )
    }
}
