package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.depth.ServerDepthMeter
import dev.sam3vision.app.live.DistanceTicker
import dev.sam3vision.app.live.predictedLayer
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CoroutineScope

/**
 * Les moteurs embarqués de la sonde derrière une seule façade (extrait de
 * `LiveProbe`, plafond 500 lignes) : la profondeur affine les distances, le
 * ticker republie les pastilles prédites toutes les [DistanceTicker.TICK_MS].
 * La profondeur est celle du **serveur** ([ServerDepthMeter]) : plus aucune
 * inférence sur le téléphone depuis le 01/11 (voir `docs/DEPTH.md`).
 */
internal class ProbeMeters(
    scope: CoroutineScope,
    depthMeter: DepthMeter? = null,
    onPredictions: (DistanceLayer?) -> Unit = {},
    clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val depth = depthMeter ?: ServerDepthMeter
    private var predicted: DistanceMeter? = null
    private var frameWidth = 0
    private var frameHeight = 0
    // Ticker sur temps réel (défaut), jamais sur `background` : un dispatcher
    // virtuel y ferait boucler `advanceUntilIdle` des tests (cf. ProbePulse).
    private val ticker = DistanceTicker(scope, clockMs = clockMs) { now ->
        val meter = predicted ?: return@DistanceTicker
        onPredictions(predictedLayer(frameWidth, frameHeight, meter.predictions(now), null))
    }

    /** Distances affinées par la profondeur du serveur, ou le sténopé tel quel. */
    fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading> =
        depth.readingsFor(frame, pinhole)

    /** Oublie moteurs et carte (nouvelle séance). */
    fun reset() {
        depth.reset()
    }

    /**
     * Dimensions de la dernière frame publiée : les pastilles prédites gardent
     * l'échelle entre deux inférences.
     */
    fun noteFrame(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
    }

    /** Republie les pastilles prédites toutes les [DistanceTicker.TICK_MS] (idempotent). */
    fun startPredictions(distances: DistanceMeter) {
        predicted = distances
        ticker.start()
    }

    /** Coupe le ticker (sortie d'écran). */
    fun stopPredictions() {
        ticker.stop()
        predicted = null
    }
}
