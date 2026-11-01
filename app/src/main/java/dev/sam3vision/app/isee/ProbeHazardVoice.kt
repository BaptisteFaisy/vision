package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.hazard.HazardSignal

/**
 * Ligne diagnostique danger d'une frame (DEBUG seul, tag `HazardVoice`) :
 * nombre de lectures, lectures à label parlé (`label/mètres/centre-x-px`),
 * signal retenu (détecté/mètres/label/centre-normalisé) et phrase du cue
 * (`null` = muet). Construite au [StringBuilder], appelée sous
 * `BuildConfig.DEBUG` : aucun log ni allocation en release. Pure, testée
 * en JVM. Miroir de `curbVoiceLine` (`ProbeCurbVoice.kt`).
 */
internal fun hazardVoiceLine(
    readings: List<DistanceReading>,
    signal: HazardSignal,
    phrase: String?,
): String {
    val line = StringBuilder(160)
    line.append("lectures=").append(readings.size).append(" dangers=[")
    var first = true
    for (reading in readings) {
        if (!HazardConcepts.isSpoken(reading.label)) continue
        if (!first) line.append(';')
        first = false
        line.append(reading.label).append('/').append(reading.meters).append("m/cx=")
            .append(centerXPx(reading))
    }
    return line.append("] signal=").append(signal.detected).append('/').append(signal.meters)
        .append("m/").append(signal.label).append("/cx=").append(signal.centerX)
        .append(" phrase=").append(phrase)
        .toString()
}

/** Centre-x brut (px) de la boîte, `null` si illisible — la ligne ne jette jamais. */
private fun centerXPx(reading: DistanceReading): Float? {
    val box = reading.box
    if (box.size < 4) return null
    return (box[0] + box[2]) / 2f
}
