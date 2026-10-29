package dev.sam3vision.app.isee

import android.content.Context
import dev.sam3vision.app.danger.DangerAlert
import dev.sam3vision.app.danger.DangerPipeline
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.depth.LiveDepthMeter
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Les deux moteurs embarqués de la sonde derrière une seule façade (extrait de
 * `LiveProbe`, plafond 500 lignes) : la profondeur affine les distances, le
 * danger guette les personnes qui approchent. Mêmes frames capturées offertes
 * aux deux, resets communs, `null` sans contexte (tests).
 */
internal class ProbeMeters(
    scope: CoroutineScope,
    background: CoroutineDispatcher,
    appContext: Context?,
    depthMeter: DepthMeter?,
    depthEngine: () -> DepthBackend,
    onDangerAlert: (DangerAlert?) -> Unit,
) {
    private val depth = depthMeter
        ?: appContext?.let { ctx -> LiveDepthMeter(scope, ctx, background, depthEngine) }
    private val danger =
        appContext?.let { ctx -> DangerPipeline(scope, ctx, background, onDangerAlert) }

    /** Frame capturée aux deux moteurs (le danger voit sans attendre SAM). */
    fun offer(jpegB64: String) {
        depth?.offer(jpegB64)
        danger?.offer(jpegB64)
    }

    /** Distances affinées par la profondeur, ou le sténopé tel quel. */
    fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading> =
        depth?.readingsFor(frame, pinhole) ?: pinhole

    /** Oublie cartes, pistes et moteurs (nouvelle séance). */
    fun reset() {
        depth?.reset()
        danger?.reset()
    }
}
