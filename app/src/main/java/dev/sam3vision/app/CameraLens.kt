package dev.sam3vision.app

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.Camera
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.distance.Optics

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
fun lensSpecOf(cameraInfo: CameraInfo?): LensSpec? {
    if (cameraInfo == null) return null
    return runCatching { readLens(Camera2CameraInfo.from(cameraInfo)) }.getOrNull()
}

/** Calibrage d'un caméra déjà liée (raccourci de l'écran Direct). */
fun lensSpecOf(camera: Camera?): LensSpec? = lensSpecOf(camera?.cameraInfo)

@OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun readLens(info: Camera2CameraInfo): LensSpec? {
    val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val intrinsic = info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
    if (active != null && intrinsic != null && intrinsic.size >= 4) {
        Optics.lensSpecFromIntrinsics(
            intrinsic[0], intrinsic[1], intrinsic[2], intrinsic[3],
            active.width(), active.height(),
        )?.let { return it }
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
    )
}