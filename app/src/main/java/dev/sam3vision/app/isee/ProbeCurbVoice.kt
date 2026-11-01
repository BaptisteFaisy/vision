package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbConcepts
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.distance.DistanceReading

/**
 * Voix de bordure de la sonde : la ligne diagnostique `CurbVoice` (DEBUG) et la
 * porte de parole des paliers proches. Sorties de `ProbeWiring.kt` pour que
 * celui-ci reste sous la limite de 500 lignes (garde CI) : la logique est la
 * même, pure et testée en JVM.
 */

/**
 * Ligne diagnostique bordure d'une frame (DEBUG seul, tag `CurbVoice`) : nombre de
 * lectures, lectures à label bordure (`label/mètres/bas-de-boîte`), signal lissé
 * (détecté/mètres/palier/bateau), phrase du cue (`null` = muet) et Traversée.
 * Construite au [StringBuilder], appelée sous `BuildConfig.DEBUG` : aucun log ni
 * allocation en release. Pure, testée en JVM.
 */
internal fun curbVoiceLine(
    readings: List<DistanceReading>,
    signal: CurbSignal,
    phrase: String?,
    crossingActive: Boolean,
): String {
    val line = StringBuilder(160)
    line.append("lectures=").append(readings.size).append(" bordures=[")
    var first = true
    for (reading in readings) {
        if (!CurbConcepts.isCurb(reading.label)) continue
        if (!first) line.append(';')
        first = false
        line.append(reading.label).append('/').append(reading.meters).append("m/bas=")
            .append(reading.box.getOrNull(3))
    }
    return line.append("] signal=").append(signal.detected).append('/').append(signal.meters)
        .append("m/").append(signal.tier).append("/flat=").append(signal.flatPass)
        .append(" phrase=").append(phrase).append(" traversee=").append(crossingActive)
        .toString()
}

/**
 * Parole bordure : les paliers proches 0,7 / 0,5 m ([CurbTier.NEAR])
 * passent toujours, même en Traversée et même après un stop (une alerte
 * de sécurité ne se tait pour rien) ; la détection et les paliers doux
 * ([CurbTier.FAR]) et le bateau ([CurbTier.NONE]) sont coupés en
 * Traversée. Pure, testée en JVM.
 */
internal fun shouldSpeakCurb(cue: CurbCue, crossingActive: Boolean): Boolean =
    cue.tier == CurbTier.NEAR || !crossingActive
