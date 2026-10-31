package dev.sam3vision.app.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Lacet temps réel depuis le vecteur de rotation : le scan traversée y lit
 * si l'utilisateur a tourné le téléphone vers le côté demandé. Même motif
 * que [PitchMeter] (écoute `SENSOR_DELAY_UI`, ~60 ms : les frames sont plus
 * lentes, le cap est toujours frais), sans passe-bas : le seuil de virage
 * (30°) absorbe le bruit de boussole (quelques degrés), et un filtre
 * circulaire mentirait au passage ±180°.
 *
 * Sans capteur, [start] ne fait rien et [yawDeg] reste `null` : le scan
 * bascule sur son repli temporisé. [stop] coupe l'écoute et oublie le cap
 * (repli explicite, pas de cap périmé).
 */
class YawMeter(context: Context) {

    private val endpoint = RotationVector.sensorOf(context)
    private val sensors: SensorManager? = endpoint?.first
    private val sensor: Sensor? = endpoint?.second

    /** Vrai si le téléphone a un vecteur de rotation ; faux = repli temporisé. */
    val hasSensor: Boolean get() = sensor != null

    /** Dernier cap (degrés, −180..180), `null` tant qu'aucun échantillon. */
    @Volatile
    var yawDeg: Float? = null
        private set

    /** Horodatage (ms) du dernier échantillon, 0 = aucun. */
    @Volatile
    var lastAtMs: Long = 0L
        private set

    private val listener = YawListener()

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

    /** Coupe l'écoute et oublie le cap. */
    fun stop() {
        started = false
        try {
            sensors?.unregisterListener(listener)
        } catch (_: RuntimeException) {
        }
        yawDeg = null
        lastAtMs = 0L
    }

    /** Vecteur de rotation → cap brut (degrés), échantillon vide ou fou ignoré. */
    private fun onRotation(values: FloatArray) {
        val orientation = RotationVector.orientationOf(values) ?: return
        val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (!yaw.isFinite()) return
        yawDeg = yaw
        lastAtMs = System.currentTimeMillis()
    }

    private inner class YawListener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) onRotation(event.values)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
}
