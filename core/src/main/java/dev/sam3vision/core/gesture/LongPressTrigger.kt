package dev.sam3vision.core.gesture

import kotlin.math.hypot

/**
 * Machine à états de l'appui long, sans dépendance Android (testable en JVM).
 *
 * Cycle : [onDown] arme un délai de [longPressMs] (« Pending »). Si le doigt ne
 * glisse pas de plus de [slopPx] et qu'aucun doigt supplémentaire n'arrive, le
 * délai déclenche [Listener.onHoldStart] (« Holding »). Ensuite un [onUp], un
 * [onCancel] ou un doigt supplémentaire clôturent la tenue une seule fois avec
 * [Listener.onHoldEnd] — l'audio déjà capté reste exploitable.
 *
 * Un glissement ou un relâchement avant le seuil annulent sans rien déclencher :
 * l'appui long ne vole jamais un geste de navigation (défilement, balayage).
 */
class LongPressTrigger(
    private val scheduler: DelayScheduler,
    private val listener: Listener,
    private val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
    private val slopPx: Float = DEFAULT_SLOP_PX,
) {

    /** Reporte [action] de [delayMs] ; rend la fonction d'annulation. */
    fun interface DelayScheduler {
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
    }

    /** Rappels d'état, émis sur le thread du [scheduler] (main thread dans l'app). */
    interface Listener {
        /** Seuil atteint : ouvrir le micro. */
        fun onHoldStart()

        /** Tenue terminée (relâchement, annulation, doigt supplémentaire). */
        fun onHoldEnd()
    }

    enum class Phase { Idle, Pending, Holding }

    var phase: Phase = Phase.Idle
        private set

    val holding: Boolean get() = phase == Phase.Holding

    private var cancelTimer: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    /** Premier doigt posé. Ignoré si une tenue est déjà en cours (elle garde la main). */
    fun onDown(x: Float, y: Float) {
        if (phase == Phase.Holding) return
        reset()
        downX = x
        downY = y
        phase = Phase.Pending
        cancelTimer = scheduler.schedule(longPressMs) {
            if (phase == Phase.Pending) {
                phase = Phase.Holding
                listener.onHoldStart()
            }
        }
    }

    /** Glissement : au-delà du seuil pendant l'attente, l'appui long est abandonné. */
    fun onMove(x: Float, y: Float) {
        if (phase == Phase.Pending && hypot(x - downX, y - downY) > slopPx) reset()
    }

    /** Doigt supplémentaire : annule l'attente, ou clôt la tenue en cours. */
    fun onPointerDown() {
        when (phase) {
            Phase.Pending -> reset()
            Phase.Holding -> end()
            Phase.Idle -> Unit
        }
    }

    /** Relâchement : clôt la tenue, ou annule une attente qui n'a pas abouti. */
    fun onUp() {
        if (phase == Phase.Holding) end() else reset()
    }

    /** Annulation système : même traitement qu'un relâchement. */
    fun onCancel() = onUp()

    /** Remise à zéro inconditionnelle (vue détachée, arrêt de l'app). */
    fun reset() {
        cancelTimer?.invoke()
        cancelTimer = null
        phase = Phase.Idle
    }

    private fun end() {
        cancelTimer?.invoke()
        cancelTimer = null
        phase = Phase.Idle
        listener.onHoldEnd()
    }

    companion object {
        /** Seuil d'appui long demandé par la spec : ~300 ms (sous le seuil système de 500 ms). */
        const val DEFAULT_LONG_PRESS_MS = 300L

        /** Tolérance de glissement en pixels (≈ 8 dp sur un écran 480 dpi). */
        const val DEFAULT_SLOP_PX = 24f
    }
}