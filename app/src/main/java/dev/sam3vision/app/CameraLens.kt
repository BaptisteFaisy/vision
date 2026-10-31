package dev.sam3vision.app

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.Camera
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.distance.Optics

/** Voie de lecture du capteur : intrinsèques mesurés ou focale (mm) + tableau. */
enum class LensReadingSource {
    /** `LENS_INTRINSIC_CALIBRATION` : focale + centre optique mesurés par le driver. */
    INTRINSICS,

    /** Focale (mm) + taille physique : centre supposé au milieu du capteur. */
    SENSOR_MM,
}

/** Calibrage capteur + la voie qui l'a donné (drapeau qualité de la focale réelle). */
data class LensReading(val spec: LensSpec, val source: LensReadingSource)

/**
 * Champ de vision réel du capteur, lu par l'interop Camera2 : la focale (mm) et
 * la taille physique du capteur donnent les deux angles — ou, mieux, la
 * calibration intrinsèque (`LENS_INTRINSIC_CALIBRATION`, focale + centre optique
 * en pixels) quand le driver l'expose. `null` si le driver ne répond rien
 * d'exploitable — l'appelant retombe alors sur le réglage utilisateur, puis sur
 * [LensSpec.Default].
 *
 * Le direct analyse des frames réduites au plus grand côté de 512 px : réduire
 * une image ne change pas son champ de vision, seulement sa focale *en pixels*,
 * que [LensSpec.focalPx] recalcule pour chaque frame.
 *
 * La géométrie (recadrage du tableau de pixels, bornes, angles) vit dans
 * [Optics.lensSpec] — testée sans téléphone ; ici on ne fait que lire le driver.
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
fun lensSpecOf(cameraInfo: CameraInfo?): LensSpec? = lensReadingOf(cameraInfo)?.spec

/** Calibrage d'un caméra déjà liée (raccourci de l'écran Direct). */
fun lensSpecOf(camera: Camera?): LensSpec? = lensSpecOf(camera?.cameraInfo)

/**
 * Même lecture, avec sa provenance ([LensReadingSource]) : les intrinsèques
 * mesurés valent mieux que la focale (mm) au centre supposé. `null` si le
 * driver est muet — même repli que [lensSpecOf].
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
fun lensReadingOf(cameraInfo: CameraInfo?): LensReading? {
    if (cameraInfo == null) return null
    return runCatching { readLensDetailed(Camera2CameraInfo.from(cameraInfo)) }.getOrNull()
}

/** Calibrage détaillé d'une caméra déjà liée (voir [lensReadingOf]). */
fun lensReadingOf(camera: Camera?): LensReading? = lensReadingOf(camera?.cameraInfo)

/**
 * Zone de référence de `LENS_INTRINSIC_CALIBRATION` : la calibration est exprimée
 * dans le repère **avant correction** de la distorsion
 * (`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`), pas la zone active — les deux
 * diffèrent quand le driver redresse (auquel cas lire contre la zone active
 * fausserait focale et centre tout en restant « plausible »). Repli sur la zone
 * active si le driver ne donne pas la zone avant correction.
 *
 * Limite connue : le zoom (`SCALER_CROP_REGION`) recadre autour d'un centre qui
 * n'est plus celui-ci — l'app ne zoome pas, donc le cas ne se présente pas, mais
 * un futur zoom devra relire le crop ou invalider ce calibrage.
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun readLensDetailed(info: Camera2CameraInfo): LensReading? {
    val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val intrinsic = info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
    if (intrinsic != null && intrinsic.size >= 4) {
        val reference =
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: active
        if (reference != null) {
            Optics.lensSpecFromIntrinsics(
                intrinsic[0], intrinsic[1], intrinsic[2], intrinsic[3],
                reference.width(), reference.height(),
            )?.let { return LensReading(it, LensReadingSource.INTRINSICS) }
        }
    }
    val focalMm = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.firstOrNull()
    val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val array = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    if (focalMm == null || physical == null || active == null || array == null) return null
    return Optics.lensSpec(
        focalMm,
        physical.width,
        physical.height,
        active.width(),
        active.height(),
        array.width,
        array.height,
    )?.let { LensReading(it, LensReadingSource.SENSOR_MM) }
}
