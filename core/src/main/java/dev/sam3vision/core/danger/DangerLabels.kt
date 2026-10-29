package dev.sam3vision.core.danger

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.Detection

/**
 * Offset des ids danger dans le lisseur de distances : le journal SAM et le
 * tracker YOLO numérotent tous deux depuis 1 — sans offset, une piste danger
 * serait lissée avec l'historique d'une piste SAM.
 */
const val DANGER_SMOOTHER_OFFSET = 1_000_000

/** Dernière frame analysée par le pipeline : pistes + dimensions (pour les distances). */
data class DangerSnapshot(
    val tracks: List<DangerTrack>,
    val width: Int,
    val height: Int,
) {
    companion object {
        val Empty = DangerSnapshot(emptyList(), 0, 0)
    }
}

/** Piste normalisée → détection en pixels (pour la mesure sténopé). */
fun DangerTrack.toDetection(width: Int, height: Int): Detection =
    Detection(
        label,
        score,
        listOf(box[0] * width, box[1] * height, box[2] * width, box[3] * height),
        width,
        height,
    )

/**
 * Pistes danger + lectures sténopé → labels placés pour la voix EXISTANTE
 * (guidage : contournement ≤ 3 m, stop au contact ; annonce des positions).
 * Appariement par id ([DANGER_SMOOTHER_OFFSET] défait) ; seules les pistes
 * mesurées parlent — sans distance, le guidage bloquerait par prudence même
 * pour une personne lointaine. Pur, testé en JVM.
 */
fun dangerPlacedLabels(
    tracks: List<DangerTrack>,
    readings: List<DistanceReading>,
): List<PlacedLabel> {
    val byTrack = readings.associateBy { it.trackId - DANGER_SMOOTHER_OFFSET }
    return tracks.mapNotNull { track ->
        val reading = byTrack[track.id] ?: return@mapNotNull null
        if (track.label.isBlank() || track.box.size < 4) return@mapNotNull null
        PlacedLabel(track.label, track.centerX, track.centerY, reading.meters, track.box[0], track.box[2])
    }
}
