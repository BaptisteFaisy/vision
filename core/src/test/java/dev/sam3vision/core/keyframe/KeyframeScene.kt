package dev.sam3vision.core.keyframe

import kotlin.math.sin

/**
 * Horloge virtuelle : le banc avance le temps, il ne **dort** jamais. Tous les tests du
 * scheduler sont donc instantanés et reproductibles.
 */
class FakeClock(private var nowMs: Long = 0L) {

    fun read(): Long = nowMs

    fun advance(ms: Long): Long {
        nowMs += ms
        return nowMs
    }
}

/**
 * Vérité terrain synthétique d'un scénario — **inventée**, pas mesurée (l'erreur de reprojection
 * réelle est produite par u11 ; le seuil de 0,12 est à recaler sur elle).
 *
 * L'erreur vrai monte avec la dérive ([driftPerSec] par seconde depuis la frame clé), reçoit un
 * bruit d'estimateur déterministe, et **retombe** quand un keyframe neuf arrive : c'est ce qui
 * donne un sens à la comparaison entre cadence fixe et scheduler.
 */
class KeyframeScene(
    val durationMs: Long,
    private val driftPerSec: Float = 0f,
    private val noiseAmplitude: Float = 0f,
    val cutTimesMs: List<Long> = emptyList(),
    private val masksLostFromMs: Long = Long.MAX_VALUE,
) {

    /** Signal « réel » à l'instant [nowMs], pour des masques issus de la frame clé prise à [keyframeAtMs]. */
    fun signalAt(nowMs: Long, keyframeAtMs: Long): FrameSignal {
        val ageSec = if (keyframeAtMs < 0L) nowMs / 1000f else (nowMs - keyframeAtMs) / 1000f
        val cutting = cutTimesMs.any { nowMs >= it && nowMs < it + CUT_WINDOW_MS }
        val noise = noiseAmplitude * sin(nowMs * NOISE_RAD_PER_MS).toFloat()
        val drift = (driftPerSec * ageSec + noise).coerceIn(0f, 1f)
        return FrameSignal(
            reprojectionError = if (cutting) 1f else drift,
            sceneChange = if (cutting) 1f else 0f,
            objectsPresent = nowMs < masksLostFromMs,
        )
    }

    companion object {
        /** Pas de la boucle virtuelle : 30 fps, comme la propagation locale (u10/u11). */
        const val TICK_MS = 33L

        /** Durée pendant laquelle une coupure de scène est « visible » par l'estimateur. */
        const val CUT_WINDOW_MS = 100L

        private const val NOISE_RAD_PER_MS = 0.0031
    }
}