package dev.sam3vision.core.distance

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimation filtrée de la distance d'une piste.
 *
 * @property meters distance filtrée en mètres.
 * @property velocityMps vitesse radiale estimée en m/s (négative en approche).
 * @property variance variance de la position (m²) : l'incertitude courante.
 */
data class KalmanEstimate(
    val meters: Float,
    val velocityMps: Float,
    val variance: Float,
)

/**
 * Filtre de Kalman position+vitesse, par piste.
 *
 * À 1-2 images par seconde, une moyenne mobile exponentielle traîne derrière un
 * objet qui s'approche ; estimer la vitesse en plus de la position suit
 * l'approche sans retard systématique. Le pas `dt` est variable (clampé
 * 0,05..5 s) car les détections n'arrivent pas à cadence fixe, et le bruit de
 * mesure `R` est adapté par la confiance : une mesure peu sûre pèse moins.
 *
 * Comme le lisseur EMA, un bond franc (innovation au-delà de 3 sigma plus une
 * marge de 50 % de la prédiction) provoque une réinitialisation douce : la
 * piste repart de la mesure brute au lieu de traîner sur une valeur morte.
 * Une piste muette depuis plus de [timeoutMs] est oubliée. Pur JVM,
 * déterministe : mêmes entrées, mêmes sorties.
 */
class DistanceKalman(
    private val processNoise: Float = 0.01f,
    private val measurementNoise: Float = 0.25f,
    private val timeoutMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Track(
        var pos: Float,
        var vel: Float,
        var p00: Float,
        var p01: Float,
        var p11: Float,
        var atMs: Long,
    )

    private val tracks = HashMap<Int, Track>()

    /** Nombre de pistes suivies (diagnostic et tests). */
    @Synchronized
    fun size(): Int = tracks.size

    /** Oublie toutes les pistes (nouvelle session). */
    @Synchronized
    fun reset() {
        tracks.clear()
    }

    /**
     * Distance filtrée de [rawMeters] pour la piste [trackId].
     *
     * [confidence] entre 0 et 1 (clampée 0,05..1) : 1 = mesure pleinement
     * fiable. Le premier appel d'une piste rend la mesure brute, vitesse nulle.
     */
    @Synchronized
    fun filter(
        trackId: Int,
        rawMeters: Float,
        confidence: Float = 1f,
        atMs: Long = clock(),
    ): KalmanEstimate {
        val r = measurementNoise / confidence.coerceIn(MIN_CONFIDENCE, 1f)
        val previous = tracks[trackId]?.takeIf { atMs - it.atMs <= timeoutMs }
        val estimate = if (previous == null) {
            initTrack(trackId, rawMeters, atMs)
        } else {
            updateTrack(trackId, previous, rawMeters, r, atMs)
        }
        trim(atMs)
        return estimate
    }

    private fun initTrack(trackId: Int, raw: Float, atMs: Long): KalmanEstimate {
        tracks[trackId] = Track(raw, 0f, measurementNoise, 0f, INITIAL_VELOCITY_VARIANCE, atMs)
        return KalmanEstimate(raw, 0f, measurementNoise)
    }

    private fun updateTrack(
        trackId: Int,
        track: Track,
        raw: Float,
        r: Float,
        atMs: Long,
    ): KalmanEstimate {
        val dt = ((atMs - track.atMs) / 1_000f).coerceIn(MIN_DT_S, MAX_DT_S)
        val dt2 = dt * dt
        val predPos = track.pos + track.vel * dt
        val pp00 = track.p00 + 2f * dt * track.p01 + dt2 * track.p11 +
            processNoise * dt2 * dt2 / 4f
        val pp01 = track.p01 + dt * track.p11 + processNoise * dt2 * dt / 2f
        val pp11 = track.p11 + processNoise * dt2
        val innovation = raw - predPos
        val s = pp00 + r
        val gate = GATE_SIGMAS * sqrt(s) + JUMP_RATIO * abs(predPos)
        if (abs(innovation) > gate) return initTrack(trackId, raw, atMs)
        val k0 = pp00 / s
        val k1 = pp01 / s
        track.pos = predPos + k0 * innovation
        track.vel += k1 * innovation
        track.p00 = (1f - k0) * pp00
        track.p01 = (1f - k0) * pp01
        track.p11 = pp11 - k1 * pp01
        track.atMs = atMs
        return KalmanEstimate(track.pos, track.vel, track.p00)
    }

    private fun trim(atMs: Long) {
        tracks.entries.removeAll { atMs - it.value.atMs > timeoutMs }
    }

    private companion object {
        const val MIN_DT_S = 0.05f
        const val MAX_DT_S = 5f
        const val MIN_CONFIDENCE = 0.05f
        const val GATE_SIGMAS = 3f
        const val JUMP_RATIO = 0.5f
        const val INITIAL_VELOCITY_VARIANCE = 0.25f
    }
}
