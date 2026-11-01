package dev.sam3vision.app.isee

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import dev.sam3vision.core.exposure.ExposureGovernor

/**
 * Réglages capteur iSee, extraits de `IseeCameraHost` (plafond 500 lignes) :
 * tuning Camera2 ([applySensorTuning]) et gouverneur de sous-exposition
 * ([IseeExposureDriver]) contre l'aperçu cramé en plein jour.
 */

/**
 * Gouverneur de sous-exposition branché sur la mesure lumière des frames :
 * quand la scène est cramée, il descend la compensation cran par cran ; quand
 * elle redevient sombre, il remonte vers 0 — jamais au-dessus. Créé à la
 * liaison depuis la plage du téléphone, inerte si le HAL n'offre rien.
 */
class IseeExposureDriver {

    /** Gouverneur pur, `null` = automatisme seul (pas de compensation négative). */
    @Volatile
    private var governor: ExposureGovernor? = null

    /** Dernier index appliqué (0 = automatisme seul). */
    @Volatile
    private var evIndex = 0

    /**
     * Depuis la plage du téléphone : la liaison repart toujours de l'index 0,
     * comme la caméra. Une ligne de log dit si le gouverneur est armé.
     */
    fun init(camera: androidx.camera.core.Camera) {
        evIndex = 0
        val range = runCatching { camera.cameraInfo.exposureState.exposureCompensationRange }.getOrNull()
        governor = if (range != null && range.lower < 0) ExposureGovernor(range.lower) else null
        Log.i(TAG, "exposition: plage ${range?.lower}..${range?.upper}, gouverneur ${if (governor == null) "off" else "on"}")
    }

    /**
     * Un cran si [mean] (0-255, `null` = mesure absente) le demande : appelé à
     * chaque frame analysée, convertie ou jetée. La compensation est globale
     * au capteur : l'aperçu, la photo, la vidéo et les frames SAM en profitent.
     */
    fun govern(mean: Double?, camera: androidx.camera.core.Camera?) {
        if (mean == null || camera == null) return
        val next = governor?.tick(mean, SystemClock.elapsedRealtime(), evIndex) ?: return
        runCatching { camera.cameraControl.setExposureCompensationIndex(next) }
            .onSuccess {
                Log.i(TAG, "exposition: EV $evIndex -> $next (moyenne ${mean.toInt()})")
                evIndex = next
            }
    }

    companion object {
        private const val TAG = "sam3vision"
    }
}

/**
 * Réglage capteur : 50 Hz (pas de bandes sous l'éclairage secteur), autofocus
 * continu (la rue nette sans tap). L'exposition reste en automatique : la
 * verrouiller (`CONTROL_AE_LOCK`) figeait la valeur du moment de la liaison —
 * souvent avant convergence — d'où un aperçu systématiquement surexposé dehors
 * (mesuré le 2026-11-01). Le pompage entre frames, s'il gêne un jour, se
 * réglera par une zone de mesure resserrée, pas par un verrou global.
 *
 * [halStages] (décidés par [CameraHalProcessing]) : étages HAL passés à OFF
 * dans le MÊME appel — `setCaptureRequestOptions` écrase le précédent, deux
 * appels séparés perdraient ce tuning. Vide = tuning d'avant, inchangé.
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
fun applySensorTuning(
    camera: androidx.camera.core.Camera,
    halStages: Set<CameraHalProcessing.HalStage> = emptySet(),
) {
    runCatching {
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        for (stage in halStages) {
            builder.setCaptureRequestOption(CameraHalProcessing.keyOf(stage), CameraHalProcessing.OFF)
        }
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())
    }.onFailure {
        Log.w("sam3vision", "réglage capteur refusé: ${it.message}")
    }
}
