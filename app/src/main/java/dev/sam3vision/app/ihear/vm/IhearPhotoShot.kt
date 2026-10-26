package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.ihear.IhearCameraActions
import dev.sam3vision.app.ihear.IhearPhotoDistances
import dev.sam3vision.app.ihear.IhearPhotoContext
import dev.sam3vision.app.ihear.IhearUiState
import dev.sam3vision.app.ihear.PhotoMeasure
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La photo iSee : celle du double tap, qui s'affiche dans la fenêtre flottante puis
 * s'évapore, et celle de l'appui long (frame vivante) qui sert de contexte à la
 * question suivante. Le gel du double tap reste prioritaire jusqu'à la question.
 *
 * Les pastilles de distance sont mesurées par [IhearPhotoDistances] (client SAM de
 * `:core`, jamais réécrit) ; chaque échec est dit à l'écran au lieu de rester muet.
 */
class IhearPhotoShot(
    private val media: MediaCacheStore,
    private val camera: () -> IhearCameraActions?,
    private val state: () -> IhearUiState,
    private val settings: () -> Settings,
    private val update: StateUpdate,
    private val scope: CoroutineScope,
    private val distances: IhearPhotoDistances = IhearPhotoDistances(),
    private val measure: suspend (File, Settings) -> PhotoMeasure =
        { file, current -> distances.measureOutcome(file, current) },
) {

    private val context = IhearPhotoContext()

    /** Minuteur d'évaporation, relancé à chaque photo en rafale. */
    private var evaporation: Job? = null

    /** Calibrage optique lu sur la caméra liée, transmis aux distances. */
    fun setLens(spec: LensSpec?) = distances.setLens(spec)

    /** Appui long : capte la frame courante s'il n'y a pas de photo gelée. Muet. */
    fun captureForHold() = context.captureForHold(camera(), media::newPhotoFile)

    /** Double tap : photo immédiate (texture, flash, évaporation en fenêtre). */
    fun takePhoto() {
        val target = media.newPhotoFile()
        val asked = camera()?.takePhoto(target) { saved -> onSaved(target, saved) }
        if (asked != true) update { it.copy(status = IhearStatusText.CAMERA_NOT_READY) }
    }

    /** Question envoyée : le gel du double tap est consommé. */
    fun releaseFreeze() = context.releaseFreeze()

    /** La photo-contexte, réencodée en base64 JPEG ; vide si aucune photo en mains. */
    fun imageBase64(): String =
        context.file?.let(::decodeScaledExact)?.let(::bitmapToJpegBase64) ?: ""

    /** Photo écrite : elle se montre dans la fenêtre, puis s'évapore (rafale : remplace). */
    private fun onSaved(target: File, saved: Boolean) {
        if (!saved) return
        context.freeze(target)
        update { it.copy(photoPath = target.path, distanceLayer = null, status = null) }
        armEvaporation(target, SHOW_MS)
        measurePastilles(target)
    }

    /** Minuteur d'évaporation de la photo ; remplacé à chaque photo d'une rafale. */
    private fun armEvaporation(target: File, showMs: Long) {
        evaporation?.cancel()
        evaporation = scope.launch {
            delay(showMs)
            if (isOnScreen(target)) update { it.copy(photoPath = null, distanceLayer = null) }
        }
    }

    /** Pastilles de distance : posées après coup, la photo reste plus longtemps lisible. */
    private fun measurePastilles(target: File) {
        scope.launch {
            val outcome = measure(target, settings())
            // La photo a pu s'évaporer (ou être remplacée) pendant l'analyse : muet.
            if (!isOnScreen(target)) return@launch
            if (outcome is PhotoMeasure.Measured) {
                update { it.copy(distanceLayer = outcome.layer) }
                armEvaporation(target, MEASURED_MS)
            } else {
                photoMeasureMessage(outcome)?.let { message -> update { it.copy(status = message) } }
            }
        }
    }

    /** Le motif se pose en état seulement si la photo est encore à l'écran. */
    private fun isOnScreen(target: File): Boolean = state().photoPath == target.path

    companion object {
        /** Une photo du double tap reste à l'écran avant de s'évaporer. */
        const val SHOW_MS = 2_600L

        /** Photo portant des pastilles : laissée plus longtemps, le temps de lire. */
        const val MEASURED_MS = 6_000L
    }
}