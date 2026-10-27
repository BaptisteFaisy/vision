package dev.sam3vision.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import dev.sam3vision.core.share.INDOOR_ACCURACY_M
import dev.sam3vision.core.share.SharedFix

/**
 * Position + cap du téléphone pendant le partage du Direct, sans dépendance
 * externe (ni Play Services, ni nouveau Gradle) : [LocationManager] (GPS puis
 * réseau) et boussole [SensorManager] (vecteur de rotation).
 *
 * [lastFix] est lu par [LiveViewModel] à chaque frame publiée : `null` tant
 * qu'aucune position n'est connue ou si la permission a été refusée. L'envoi
 * reste alors silencieux — le site garde le dernier état et l'affiche périmé.
 */
class SharePosition(context: Context) {

    private val appContext = context.applicationContext
    private val locations: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)
    private val sensors: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)

    /** Dernière position connue, lue depuis le thread du direct. */
    @Volatile
    var lastFix: SharedFix? = null
        private set

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var headingDeg: Float? = null

    private val listener = FixListener()

    /** Démarre l'écoute GPS + boussole ; sans permission, ne fait rien. */
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            locations?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                SHARE_GPS_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalArgumentException) {
            // Pas de GPS sur l'appareil : le réseau suffit pour une position grossière.
        }
        try {
            locations?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                SHARE_GPS_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /** Coupe l'écoute et oublie la position (fin du Direct). */
    fun stop() {
        try {
            locations?.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
        try {
            sensors?.unregisterListener(listener)
        } catch (_: RuntimeException) {
        }
        lastLocation = null
        headingDeg = null
        lastFix = null
    }

    /** Mémorise la position et republie le fix combiné au cap courant. */
    private fun onLocation(location: Location) {
        lastLocation = location
        lastFix = toFix(location, headingDeg)
    }

    /** Convertit le vecteur de rotation en cap [0, 360[. */
    private fun onRotation(values: FloatArray) {
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        headingDeg = ((azimuth % 360f) + 360f) % 360f
        lastLocation?.let { lastFix = toFix(it, headingDeg) }
    }

    private inner class FixListener : LocationListener, SensorEventListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) onRotation(event.values)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private companion object {
        /** Cadence GPS du partage : la même que l'envoi (un état toutes les 2 s). */
        const val SHARE_GPS_MS = 2_000L
    }
}

/**
 * Fix partageable depuis une position brute : la précision annoncée décide du
 * badge « intérieur » ([INDOOR_ACCURACY_M]), pure fonction testable sans capteur.
 */
fun toFix(location: Location, heading: Float?): SharedFix {
    val acc = if (location.hasAccuracy()) location.accuracy else NO_ACCURACY_M
    return SharedFix(
        lat = location.latitude,
        lon = location.longitude,
        accM = acc,
        headingDeg = heading,
        atMs = location.time,
        indoor = acc > INDOOR_ACCURACY_M,
    )
}

/** Précision de repli quand le fournisseur n'en annonce aucune : marquée intérieur. */
const val NO_ACCURACY_M = 50f
