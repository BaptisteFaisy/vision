package dev.sam3vision.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.sam3vision.core.motion.GyroCompensation
import dev.sam3vision.core.motion.GyroDelta
import dev.sam3vision.core.motion.StampedDelta

/**
 * Deltas de rotation caméra horodatés depuis le vecteur de rotation (PISTE 4).
 *
 * Même motif que [dev.sam3vision.app.pose.PitchMeter] (vecteur de rotation +
 * `getOrientation`, `start` idempotent, repli silencieux sans capteur), mais en
 * [SensorManager.SENSOR_DELAY_GAME] (~20 ms : il faut au moins un échantillon par
 * intervalle inter-frames à 30 fps) et en retenant l'historique : [deltaBetween]
 * rend la rotation entre deux instants passés, pas seulement l'orientation courante.
 *
 * Convention des deltas : celle de [GyroDelta] (signe = déplacement image :
 * `dYaw > 0` = scène vers la droite). Le passage capteur → image applique
 * [YAW_SIGN]/[PITCH_SIGN], lus depuis la doc Android et `docs/POSE-IMU.md` —
 * **à valider sur téléphone** (pan gauche puis tilt haut, comparer aux `dx, dy`
 * mesurés) avant tout usage décisionnel.
 *
 * Hypothèses : téléphone tenu approximativement vertical, portrait, caméra arrière
 * (le cas iSee), roulis ≈ 0. Hors de ce domaine les deltas restent des rotations
 * capteur valides mais leur projection image se dégrade — le garde-fou de rotation
 * excessive du core ([GyroCompensation.MAX_DELTA_RAD]) coupe les cas extrêmes.
 *
 * Thread-safety : écrit depuis le thread d'écoute (voir [start]), lu depuis le
 * thread du direct — historique sous verrou, `latest` volatile.
 */
class GyroSource(context: Context) {

    /**
     * Orientation absolue datée : [yawRad] = azimut `getOrientation[0]` ([−π, π]),
     * [pitchRad] = `getOrientation[1]` brut (l'offset +π/2 de `PitchMath` s'annule
     * dans les deltas, inutile ici).
     *
     * Deux horodatages : [atNs] = `event.timestamp` (ns, base
     * `elapsedRealtimeNanos` — la précision capteur, pour un appariement futur aux
     * timestamps `ImageProxy`) ; [atMs] = `System.currentTimeMillis()` à la
     * réception, la **même base que les frames du Direct** (`LiveFrameDispatcher`)
     * — c'est elle que [deltaBetween] utilise, la latence de livraison (~ms) étant
     * négligeable devant 33 ms de frame.
     */
    data class Sample(val yawRad: Float, val pitchRad: Float, val atNs: Long, val atMs: Long)

    private val sensors: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Vrai si le téléphone a un vecteur de rotation ; faux = verdicts visuels seuls. */
    val hasSensor: Boolean get() = sensor != null

    /** Dernier échantillon, `null` tant qu'aucun (ou sans capteur). */
    @Volatile
    var latest: Sample? = null
        private set

    private val lock = Any()

    /** Historique borné (voir [MAX_SAMPLES]), du plus vieux au plus récent. */
    private val history = ArrayDeque<Sample>()

    private val listener = GyroListener()

    @Volatile
    private var started = false

    /**
     * Démarre l'écoute ; sans capteur, ne fait rien. Idempotent. Comme
     * [dev.sam3vision.app.pose.PitchMeter], sans `Handler` : appeler depuis un
     * thread à `Looper` (le main), sinon les callbacks ne sont jamais délivrés.
     */
    fun start() {
        val manager = sensors
        val vector = sensor
        if (manager == null || vector == null || started) return
        started = true
        try {
            manager.registerListener(listener, vector, SensorManager.SENSOR_DELAY_GAME)
        } catch (_: RuntimeException) {
            started = false
        }
    }

    /** Coupe l'écoute et oublie l'historique (pas de delta périmé à la reprise). */
    fun stop() {
        started = false
        try {
            sensors?.unregisterListener(listener)
        } catch (_: RuntimeException) {
        }
        latest = null
        synchronized(lock) { history.clear() }
    }

    /**
     * Rotation entre [fromMs] et [toMs] (ms, base `currentTimeMillis` : les dates de
     * frames du Direct), `null` sans avis : capteur absent, historique vide, bornes
     * sans échantillon proche (voir [MATCH_TOLERANCE_MS]), ou intervalle inversé.
     *
     * L'âge du delta (`[nowMs] − borne`) est porté par le [StampedDelta] : c'est le
     * core ([GyroCompensation.residual]) qui le refuse s'il est périmé.
     */
    fun deltaBetween(fromMs: Long, toMs: Long, nowMs: Long = System.currentTimeMillis()): StampedDelta? {
        if (toMs < fromMs) return null
        val (from, to) = synchronized(lock) { nearest(history, fromMs) to nearest(history, toMs) }
        if (from == null || to == null) return null
        if (from === to && fromMs != toMs) return null
        return StampedDelta(
            GyroDelta(
                YAW_SIGN * GyroCompensation.shortestDelta(from.yawRad, to.yawRad),
                PITCH_SIGN * GyroCompensation.shortestDelta(from.pitchRad, to.pitchRad),
            ),
            nowMs - to.atMs,
        )
    }

    /** Vecteur de rotation → orientation absolue → historique borné. */
    private fun onRotation(values: FloatArray, eventNs: Long) {
        if (values.size < 3) return
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        if (!orientation[0].isFinite() || !orientation[1].isFinite()) return
        val sample = Sample(orientation[0], orientation[1], eventNs, System.currentTimeMillis())
        latest = sample
        synchronized(lock) {
            history.addLast(sample)
            while (history.size > MAX_SAMPLES) history.removeFirst()
        }
    }

    private inner class GyroListener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) onRotation(event.values, event.timestamp)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    companion object {
        /**
         * Signe yaw capteur → image : l'azimut est une boussole (sens horaire) ; un
         * pan caméra vers la gauche le fait décroître pendant que la scène part vers
         * la droite (`dYaw > 0`) — d'où −1. Vrai quelle que soit l'orientation de
         * départ (rotation relative). À valider sur téléphone.
         */
        const val YAW_SIGN = -1f

        /**
         * Signe pitch capteur → image : `getOrientation[1]` vaut −π/2 en portrait
         * vertical et 0 à plat (`docs/POSE-IMU.md`) ; un tilt vers le haut le fait
         * décroître pendant que la scène part vers le bas (`dPitch > 0`) — d'où −1.
         * À valider sur téléphone.
         */
        const val PITCH_SIGN = -1f

        /**
         * Écart maximal (ms) entre une borne demandée et son échantillon : à ~20 ms
         * d'échantillonnage, 100 ms = 5 trous — au-delà, pas d'avis plutôt qu'un
         * delta sur de mauvaises bornes.
         */
        const val MATCH_TOLERANCE_MS = 100L

        /** Historique : 128 échantillons ≈ 2,5 s à 20 ms — largement un intervalle serveur. */
        const val MAX_SAMPLES = 128
    }
}

/**
 * Échantillon le plus proche de [atMs], `null` si le plus proche est au-delà de
 * [GyroSource.MATCH_TOLERANCE_MS]. L'historique est trié par dates croissantes :
 * on s'arrête dès qu'on s'éloigne.
 */
private fun nearest(history: ArrayDeque<GyroSource.Sample>, atMs: Long): GyroSource.Sample? {
    var best: GyroSource.Sample? = null
    var bestGap = Long.MAX_VALUE
    for (sample in history) {
        val gap = kotlin.math.abs(sample.atMs - atMs)
        if (gap < bestGap) {
            best = sample
            bestGap = gap
        } else if (sample.atMs > atMs) {
            break
        }
    }
    return best?.takeIf { bestGap <= GyroSource.MATCH_TOLERANCE_MS }
}
