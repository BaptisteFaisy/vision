package dev.sam3vision.app.isee

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

/**
 * Gate de la coupe des post-traitements HAL (mesure latence : `age` = expo +
 * readout + HAL + livraison — le HAL Samsung applique par défaut rehaut de
 * contours, débruitage, shading, tonemap : 10-30 ms par frame).
 *
 * La décision ([selectStages]) est pure et testée en JVM ; la lecture des
 * modes annoncés ([readAvailableModes]) touche le téléphone et ne lève jamais
 * (repli défaut silencieux). L'application passe par [applySensorTuning] avec
 * les étages décidés : UN seul `setCaptureRequestOptions` (l'appel écrase le
 * précédent — deux appels séparés perdraient l'antibanding et l'AF continu).
 *
 * Coupés (chaque OFF = « aucun traitement appliqué », doc AOSP
 * `CameraMetadata`) :
 * - contours ([HalStage.EDGE]) : le rehaut (accutance) est une passe
 *   convolutive coûteuse ; SAM segmente à 320 px, il n'en a pas besoin ;
 * - bruit ([HalStage.NOISE]) : le débruitage spatial/temporel coûte plusieurs
 *   ms HAL ; SAM travaille à 518 px, robuste au bruit (mesure détection) ;
 * - shading ([HalStage.SHADING]) : gain de vignettage par pixel ; OFF laisse
 *   les coins sombres, sans effet sur la segmentation ;
 * - pixels chauds ([HalStage.HOT_PIXEL]) : quelques points brillants isolés
 *   possibles, négligeables à 320 px.
 *
 * Jamais touchés : le tonemap (`TONEMAP_MODE_OFF` = sortie linéaire, image
 * très sombre — risque détection, gain non prouvé) et, évidemment,
 * l'AE/AWB/AF.
 */
object CameraHalProcessing {

    /** Étages HAL coupables par le drapeau, un par mode Camera2 passé à OFF. */
    enum class HalStage {
        EDGE,
        NOISE,
        SHADING,
        HOT_PIXEL,
    }

    /**
     * Modes annoncés par le capteur (`*_AVAILABLE_*` de [CameraCharacteristics],
     * `null` = caractéristique illisible). Des `List<Int>` maison plutôt que les
     * `IntArray` Android : la décision, elle, se prouve en JVM.
     */
    data class AvailableModes(
        val edge: List<Int>?,
        val noise: List<Int>?,
        val shading: List<Int>?,
        val hotPixel: List<Int>?,
    )

    /**
     * Valeur OFF des quatre modes : `EDGE_MODE_OFF`, `NOISE_REDUCTION_MODE_OFF`,
     * `SHADING_MODE_OFF` et `HOT_PIXEL_MODE_OFF` valent tous 0 (AOSP). Constante
     * maison : les champs Android lèvent « Stub! » en test JVM.
     */
    const val OFF = 0

    /**
     * Étages à couper, ou vide (défaut, liaison inchangée) : drapeau coupé,
     * modes illisibles, ou OFF non annoncé. Chaque étage est indépendant : un
     * HAL qui n'annonce OFF que pour le bruit ne coupe que le bruit.
     */
    fun selectStages(flagOn: Boolean, available: AvailableModes?): Set<HalStage> {
        if (!flagOn || available == null) return emptySet()
        return buildSet {
            if (available.edge?.contains(OFF) == true) add(HalStage.EDGE)
            if (available.noise?.contains(OFF) == true) add(HalStage.NOISE)
            if (available.shading?.contains(OFF) == true) add(HalStage.SHADING)
            if (available.hotPixel?.contains(OFF) == true) add(HalStage.HOT_PIXEL)
        }
    }

    /**
     * Modes annoncés par la caméra liée, `null` si illisibles : implémentation
     * non-Camera2, caractéristique manquante — tous les cas retombent sur le
     * défaut, jamais d'exception vers l'appelant.
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun readAvailableModes(camera: androidx.camera.core.Camera): AvailableModes? = runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        AvailableModes(
            edge = info.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList(),
            noise = info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList(),
            shading = info.getCameraCharacteristic(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toList(),
            hotPixel = info.getCameraCharacteristic(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toList(),
        )
    }.getOrNull()

    /**
     * Clé Camera2 de l'étage (borne de conversion unique, côté téléphone).
     */
    fun keyOf(stage: HalStage): CaptureRequest.Key<Int> = when (stage) {
        HalStage.EDGE -> CaptureRequest.EDGE_MODE
        HalStage.NOISE -> CaptureRequest.NOISE_REDUCTION_MODE
        HalStage.SHADING -> CaptureRequest.SHADING_MODE
        HalStage.HOT_PIXEL -> CaptureRequest.HOT_PIXEL_MODE
    }

    /**
     * Décision + application à la liaison : drapeau éteint, les modes ne sont
     * même pas lus et le tuning reste celui d'avant — une seule lecture, un
     * seul log, un seul appel tuning.
     */
    fun resolveAndApply(flagOn: Boolean, camera: androidx.camera.core.Camera) {
        val stages = if (flagOn) selectStages(true, readAvailableModes(camera)) else emptySet()
        Log.i(
            "sam3vision",
            "camhal: drapeau=$flagOn appliqué=${if (stages.isEmpty()) "défaut" else stages.joinToString("+") { it.name.lowercase() }}",
        )
        applySensorTuning(camera, stages)
    }
}
