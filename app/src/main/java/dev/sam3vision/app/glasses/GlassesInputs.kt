package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import android.util.Log
import dev.sam3vision.core.glasses.CaptureAction
import dev.sam3vision.core.glasses.CaptureGestureBuffer
import dev.sam3vision.core.glasses.CapturePress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bouton capture des lunettes → actions de l'app.
 *
 * Le tick périodique est indispensable : un appui court n'est confirmé qu'après le
 * délai de grâce du buffer, sinon la photo ne partirait jamais. On n'écoute que
 * [InputSource.CAPTURE_BUTTON] : le tap et le tap-long du pavé sont réservés par le
 * système sur les lunettes sans écran (pause et arrêt de session).
 */
internal class GlassesInputs(
    private val session: DeviceSession,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onAction: (CaptureAction) -> Unit,
    private val onNote: (GlassesMessage) -> Unit,
) {
    private val gesture = CaptureGestureBuffer()
    private var attached = false
    private var pump: Job? = null
    private var listenJob: Job? = null
    private var lastEvent: Pair<CapturePressType, Long>? = null

    fun start(): Boolean {
        if (attached) return true
        return session.addInputs(
            InputsConfiguration(sources = setOf(InputSource.CAPTURE_BUTTON), consumeBack = false),
        ).fold(
            onSuccess = { inputs ->
                attached = true
                listen(inputs)
                startPump()
                true
            },
            onFailure = { error, _ ->
                onNote(GlassesMessages.inputsUnavailable(error.description))
                false
            },
        )
    }

    private fun listen(inputs: Inputs) {
        listenJob?.cancel()
        listenJob = scope.launch {
            inputs.events.collect { event -> handle(event) }
        }
    }

    private fun handle(event: InputEvent) {
        val capture = event as? InputEvent.Capture ?: return
        // Seul le bouton capture déclenche : un `Capture` venu d'une autre
        // source déclarée (pavé, bouton d'action…) ne prend jamais de photo.
        // `UNKNOWN` reste accepté (filet : le mock debug passe par ici).
        if (capture.source != InputSource.CAPTURE_BUTTON && capture.source != InputSource.UNKNOWN) {
            Log.i(TAG, "bouton lunettes : source ${capture.source} ignorée")
            return
        }
        val id = capture.pressType to capture.timestampMs
        if (id == lastEvent) {
            Log.i(TAG, "bouton lunettes : doublon ${capture.pressType} @${capture.timestampMs} ignoré")
            return
        }
        lastEvent = id
        val action = gesture.onPress(capture.pressType.toCorePress(), clock())
        Log.i(TAG, "bouton lunettes : ${capture.pressType} @${capture.timestampMs} -> ${action ?: "attente"}")
        action?.let(onAction)
    }

    private fun startPump() {
        pump?.cancel()
        pump = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                gesture.flush(clock())?.let { action ->
                    Log.i(TAG, "bouton lunettes : appui court confirmé -> $action")
                    onAction(action)
                }
            }
        }
    }

    fun stop() {
        pump?.cancel()
        pump = null
        listenJob?.cancel()
        listenJob = null
        if (attached) session.removeInputs()
        attached = false
        lastEvent = null
        gesture.reset()
    }

    private companion object {
        /** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
        const val TAG = "GlassesChain"
        const val TICK_MS = 80L
    }
}

/** `CapturePressType` du SDK → `CapturePress`, la logique pure testée en JVM. */
internal fun CapturePressType.toCorePress(): CapturePress = when (this) {
    CapturePressType.SHORT_PRESS -> CapturePress.SHORT
    CapturePressType.HOLD -> CapturePress.HOLD
    CapturePressType.DOUBLE_PRESS -> CapturePress.DOUBLE
}