package dev.sam3vision.app.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.sam3vision.core.distance.DistanceMeter

/**
 * État qualité de la pose IMU : la pose ne tombe plus à `null` en silence —
 * [PitchMeter.quality] dit toujours pourquoi la branche sol est muette, et
 * [PitchMeter.lowAccuracy] quand le driver doute de ses propres échantillons.
 */
enum class PitchQuality {
    /** Pas de vecteur de rotation : sténopé seul, par construction. */
    NO_SENSOR,

    /** Écoute coupée ([PitchMeter.stop]) : pose rendue au sténopé seul. */
    IDLE,

    /** En écoute, aucun échantillon reçu : pose pas encore connue. */
    WAITING_SAMPLE,

    /** Passe-bas en chauffe (voir [PitchMath.SETTLING_SAMPLES]) : tangage brut. */
    SETTLING,

    /** Tangage filtré et frais : la branche sol travaille. */
    OK,

    /** Dernier échantillon trop vieux ([PitchMeter.STALE_AFTER_MS]) : tangage périmé. */
    STALE,
}

/**
 * Tangage temps réel depuis le vecteur de rotation : chaque échantillon filtré
 * ([PitchMath]) rafraîchit [DistanceMeter.pose] avec la hauteur réglée. Même motif
 * que [dev.sam3vision.app.SharePosition] (vecteur de rotation + `getOrientation`),
 * cadence d'interface (~60 ms : les frames sont plus lentes, la pose est toujours fraîche).
 *
 * Sans capteur, [start] ne fait rien et la pose reste `null` : seule la branche
 * sténopé travaille, exactement comme avant — mais [quality] le dit ([PitchQuality.NO_SENSOR]).
 * [stop] coupe l'écoute et rend la pose à `null` (repli explicite, pas de tangage périmé).
 */
class PitchMeter(
    context: Context,
    private val distances: DistanceMeter,
    private val heights: CameraHeightStore = CameraHeightStore(context),
) {

    private val endpoint = RotationVector.sensorOf(context)
    private val sensors: SensorManager? = endpoint?.first
    private val sensor: Sensor? = endpoint?.second

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

    /** Échantillons finis mélangés depuis [start] (chauffe du passe-bas, voir [quality]). */
    @Volatile
    var sampleCount: Int = 0
        private set

    /**
     * Dernière précision annoncée par le driver (`SENSOR_STATUS_*`, −1 = inconnue).
     * Basse ou non fiable → [lowAccuracy] : le tangage est lu quand même (le
     * passe-bas lisse), mais la branche sol sait qu'il est douteux.
     */
    @Volatile
    var lastAccuracy: Int = ACCURACY_UNKNOWN
        private set

    /** Vrai quand le driver doute de ses échantillons (basse précision ou non fiable). */
    val lowAccuracy: Boolean
        get() = lastAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
            lastAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE

    /**
     * État qualité courant : jamais de `null` silencieux — `OK` seul dit que la
     * branche sol travaille sur du frais et stabilisé.
     */
    val quality: PitchQuality
        get() {
            if (!hasSensor) return PitchQuality.NO_SENSOR
            if (!started) return PitchQuality.IDLE
            if (pitchRad == null) return PitchQuality.WAITING_SAMPLE
            if (System.currentTimeMillis() - lastAtMs > STALE_AFTER_MS) return PitchQuality.STALE
            if (sampleCount < PitchMath.SETTLING_SAMPLES) return PitchQuality.SETTLING
            return PitchQuality.OK
        }

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
        sampleCount = 0
        lastAccuracy = ACCURACY_UNKNOWN
        distances.pose = null
    }

    /** Vecteur de rotation → tangage filtré → pose fraîche (hauteur relue à chaque fois). */
    private fun onRotation(values: FloatArray) {
        val orientation = RotationVector.orientationOf(values) ?: return
        val sample = PitchMath.pitchRadFromOrientation(orientation[1])
        if (sample.isFinite()) sampleCount++
        val filtered = PitchMath.lowPass(pitchRad, sample)
        pitchRad = filtered
        lastAtMs = System.currentTimeMillis()
        distances.pose = PitchMath.poseOrNull(heights.get(), filtered)
    }

    private inner class PitchListener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) onRotation(event.values)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) lastAccuracy = accuracy
        }
    }

    companion object {
        /** Précision annoncée par aucun driver : lue comme « inconnue », jamais basse. */
        const val ACCURACY_UNKNOWN = -1

        /**
         * Âge maximal d'un tangage cru frais (1 s, large devant les ~60 ms de
         * `SENSOR_DELAY_UI`) : au-delà, [quality] passe à [PitchQuality.STALE].
         */
        const val STALE_AFTER_MS = 1_000L
    }
}
