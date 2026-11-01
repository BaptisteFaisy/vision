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
 * Phone position + heading during Direct sharing, with no outside dependency
 * (no Play Services, no new Gradle): [LocationManager] (GPS then network)
 * and [SensorManager] compass (rotation vector).
 *
 * [lastFix] is read by [LiveViewModel] on every published frame: `null`
 * while no position is known or when permission was refused. Sending then
 * stays silent — the site keeps the last state and shows it stale.
 */
class SharePosition(context: Context) {

    private val appContext = context.applicationContext
    private val locations: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)
    private val sensors: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)

    /** Last known position, read from the live thread. */
    @Volatile
    var lastFix: SharedFix? = null
        private set

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var headingDeg: Float? = null

    private val listener = FixListener()

    /** Starts the GPS + compass listen; with no permission, does nothing. */
    @SuppressLint("MissingPermission")
    fun start() {
        seedLastKnown()
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
            // No GPS on the device: network is enough for a coarse position.
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

    /**
     * Seeds the position with the last known (GPS or network, whichever is
     * fresher): without it, a phone opening iSee indoors published nothing
     * until GPS locked. A position may be old: the relay timestamps the send,
     * and moves require two fresh fixes (see the caregiver page).
     */
    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        val gps = runCatching { locations?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net = runCatching { locations?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val best = if ((gps?.time ?: 0L) >= (net?.time ?: 0L)) gps else net
        if (best != null) onLocation(best)
    }

    /** Cuts the listen and forgets the position (end of the Direct). */
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

    /** Stores the position and republishes the fix combined with the current heading. */
    private fun onLocation(location: Location) {
        lastLocation = location
        lastFix = toFix(location, headingDeg)
    }

    /** Converts the rotation vector into a [0, 360[ heading. */
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
        /** Share GPS rate: same as sending (one state every 2 s). */
        const val SHARE_GPS_MS = 2_000L
    }
}

/**
 * Shareable fix from a raw position: the announced accuracy decides the
 * "indoor" badge ([INDOOR_ACCURACY_M]), a pure sensor-free testable function.
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

/** Fallback accuracy when the provider announces none: flagged indoor. */
const val NO_ACCURACY_M = 50f
