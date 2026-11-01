package dev.sam3vision.app.isee

import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.depth.ServerDepthMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame

/**
 * Le moteur embarqué de la sonde derrière une seule façade (extrait de
 * `LiveProbe`, plafond 500 lignes) : la profondeur du **serveur**
 * ([ServerDepthMeter]) affine les distances — plus aucune inférence sur le
 * téléphone depuis le 01/11 (voir `docs/DEPTH.md`).
 *
 * L'écran ne montre que SAM : les pastilles sont les lectures de la dernière
 * frame (`publishRest`), jamais des prédictions extrapolées entre deux
 * inférences (le ticker 80 ms est supprimé).
 */
internal class ProbeMeters(
    depthMeter: DepthMeter? = null,
) {
    private val depth = depthMeter ?: ServerDepthMeter

    /** Distances affinées par la profondeur du serveur, ou le sténopé tel quel. */
    fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading> =
        depth.readingsFor(frame, pinhole)

    /** Oublie moteurs et carte (nouvelle séance). */
    fun reset() {
        depth.reset()
    }
}
