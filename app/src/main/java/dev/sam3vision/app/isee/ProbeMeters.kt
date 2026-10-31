package dev.sam3vision.app.isee

import android.content.Context
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.depth.LiveDepthMeter
import dev.sam3vision.app.live.DistanceTicker
import dev.sam3vision.app.live.predictedLayer
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Les moteurs embarqués de la sonde derrière une seule façade (extrait de
 * `LiveProbe`, plafond 500 lignes) : la profondeur affine les distances, le
 * ticker republie les pastilles prédites toutes les 120 ms. Mêmes frames
 * capturées offertes au moteur, resets communs, `null` sans contexte (tests).
 */
internal class ProbeMeters(
    scope: CoroutineScope,
    background: CoroutineDispatcher,
    appContext: Context?,
    depthMeter: DepthMeter?,
    depthEngine: () -> DepthBackend,
    onPredictions: (DistanceLayer?) -> Unit = {},
    clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val depth = depthMeter
        ?: appContext?.let { ctx -> LiveDepthMeter(scope, ctx, background, depthEngine) }
    private var predicted: DistanceMeter? = null
    private var frameWidth = 0
    private var frameHeight = 0
    // Ticker sur temps réel (défaut), jamais sur `background` : un dispatcher
    // virtuel y ferait boucler `advanceUntilIdle` des tests (cf. ProbePulse).
    private val ticker = DistanceTicker(scope, clockMs = clockMs) { now ->
        val meter = predicted ?: return@DistanceTicker
        onPredictions(predictedLayer(frameWidth, frameHeight, meter.predictions(now), null))
    }

    /** Frame capturée au moteur de profondeur. */
    fun offer(jpegB64: String) {
        depth?.offer(jpegB64)
    }

    /** Distances affinées par la profondeur, ou le sténopé tel quel. */
    fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading> =
        depth?.readingsFor(frame, pinhole) ?: pinhole

    /** Oublie cartes et moteur (nouvelle séance). */
    fun reset() {
        depth?.reset()
    }

    /**
     * Dimensions de la dernière frame publiée : les pastilles prédites gardent
     * l'échelle entre deux inférences.
     */
    fun noteFrame(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
    }

    /** Republie les pastilles prédites toutes les 120 ms (idempotent). */
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
