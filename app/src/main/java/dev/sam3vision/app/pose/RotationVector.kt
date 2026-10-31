package dev.sam3vision.app.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Vecteur de rotation partagé : résolution du capteur et orientation, sans
 * état ni écoute — [PitchMeter] (tangage) et [YawMeter] (lacet du scan
 * traversée) y lisent la même source au lieu de dupliquer les appels
 * `SensorManager`.
 */
internal object RotationVector {

    /**
     * Gestionnaire + capteur du vecteur de rotation, `null` sans capteur
     * (téléphone sans IMU : les mètres restent muets, les replis prennent
     * le relais).
     */
    fun sensorOf(context: Context): Pair<SensorManager, Sensor>? {
        val managers = context.applicationContext.getSystemService(SensorManager::class.java)
            ?: return null
        val sensor = managers.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
        return managers to sensor
    }

    /**
     * Orientation (azimut, tangage, roulis, radians) depuis un échantillon
     * du vecteur, `null` si l'échantillon est vide (capteur fantaisiste :
     * ignoré, jamais propagé).
     */
    fun orientationOf(values: FloatArray): FloatArray? {
        if (values.isEmpty()) return null
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        return orientation
    }
}
