package dev.sam3vision.app.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.sam3vision.core.distance.DistanceMeter

/**
 * Tangage temps réel depuis le vecteur de rotation : chaque échantillon filtré
 * ([PitchMath]) rafraîchit [DistanceMeter.pose] avec la hauteur réglée. Même motif
 * que [dev.sam3vision.app.SharePosition] (vecteur de rotation + `getOrientation`),
 * cadence d'interface (~60 ms : les frames sont plus lentes, la pose est toujours fraîche).
 *
 * Sans capteur, [start] ne fait rien et la pose reste `null` : seule la branche
 * sténopé travaille, exactement comme avant. [stop] coupe l'écoute et rend la pose
 * à `null` (repli explicite, pas de tangage périmé).
 */
class PitchMeter(
    context: Context,
    private val distances: DistanceMeter,
    private val heights: CameraHeightStore = CameraHeightStore(context),
) {

    private val sensors: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Vrai si le téléphone a un vecteur de rotation ; faux = sténopé seul. */
    val hasSensor: Boolean get() = sensor != null

    /** Dernier tangage filtré (rad, `+` = pique), `null` tant qu'aucun échantillon. */
    @Volatile
    var pitchRad: Float? = null
        private set

    /** Horodatage (ms) du dernier échantillon, 0 = aucun. */
    @Volatile
    var lastAtMs: Long = 0L
        private set

    private val listener = PitchListener()

    @Volatile
    private var started = false

    /** Démarre l'écoute ; sans capteur, ne fait rien. Idempotent. */
    fun start() {
        val manager = sensors
        val vector = sensor
        if (manager == null || vector == null || started) return
        started = true
        try {
            manager.registerListener(listener, vector, SensorManager.SENSOR_DELAY_UI)
        } catch (_: RuntimeException) {
            started = false
        }
    }

    /** Coupe l'écoute, oublie le tangage et rend la pose au sténopé seul. */
    fun stop() {
        started = false
        try {
            sensors?.unregisterListener(listener)
        } catch (_: RuntimeException) {
        }
        pitchRad = null
        lastAtMs = 0L
        distances.pose = null
    }

    /** Vecteur de rotation → tangage filtré → pose fraîche (hauteur relue à chaque fois). */
    private fun onRotation(values: FloatArray) {
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        val filtered = PitchMath.lowPass(pitchRad, PitchMath.pitchRadFromOrientation(orientation[1]))
        pitchRad = filtered
        lastAtMs = System.currentTimeMillis()
        distances.pose = PitchMath.poseOrNull(heights.get(), filtered)
    }

    private inner class PitchListener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) onRotation(event.values)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
}
