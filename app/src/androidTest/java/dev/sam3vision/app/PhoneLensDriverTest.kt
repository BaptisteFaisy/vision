package dev.sam3vision.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sam3vision.core.distance.Optics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gardes pilote de la voie intrinsèques : caméra muette → `null` (repli
 * Réglages), et les valeurs brutes du pilote donnent un calibrage utilisable
 * dont les deux voies s'accordent. Exige un appareil (émulateur isee-sim ou
 * téléphone) : la CI ne l'exécute pas (elle ne lance que `testDebugUnitTest`).
 * `getCameraCharacteristics` ne demande aucune permission.
 */
@RunWith(AndroidJUnit4::class)
class PhoneLensDriverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun pilote_muet_donne_null() {
        assertNull(lensSpecOf(null as CameraInfo?))
        assertNull(lensReadingOf(null as CameraInfo?))
        assertNull(lensSpecOf(null as Camera?))
    }

    @Test
    fun caracteristiques_pilote_donnent_calibrage_utilisable() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        assertTrue("aucune caméra sur l'appareil", ids.isNotEmpty())
        val back = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
        val chars = manager.getCameraCharacteristics(back)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val mm = if (focal == null || physical == null || active == null || array == null) {
            null
        } else {
            Optics.lensSpec(
                focal, physical.width, physical.height,
                active.width(), active.height(), array.width, array.height,
            )
        }
        val intrinsic = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val reference = chars.get(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
        ) ?: active
        val intr = if (intrinsic == null || intrinsic.size < 4 || reference == null) {
            null
        } else {
            Optics.lensSpecFromIntrinsics(
                intrinsic[0], intrinsic[1], intrinsic[2], intrinsic[3],
                reference.width(), reference.height(),
            )
        }
        assertNotNull("le pilote ne donne aucun calibrage (ni intrinsèques ni mm)", mm ?: intr)
        if (mm != null && intr != null) {
            assertEquals(mm.horizontalFovDeg, intr.horizontalFovDeg, 1f)
            assertEquals(mm.verticalFovDeg, intr.verticalFovDeg, 1f)
        }
    }
}
