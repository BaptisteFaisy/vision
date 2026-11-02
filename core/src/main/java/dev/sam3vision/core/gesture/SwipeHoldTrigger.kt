package dev.sam3vision.core.gesture

import kotlin.math.hypot

/**
 * Machine à états du « slide vers le bas + maintien », sans dépendance
 * Android (testable en JVM). Le balayage vers le bas confirmé l'arme
 * ([arm], position du doigt à la confirmation) ; si le doigt reste posé
 * [holdMs] sans glisser de plus de [slopPx], [Listener.onSwipeHoldStart]
 * ouvre le micro destination — le relâchement clôt par
 * [Listener.onSwipeHoldEnd] (envoi). Mêmes seuils que l'appui long
 * ([LongPressTrigger]) : le maintien « se sent » pareil.
 *
 * Un glissement repris ou un relâchement avant le seuil annulent sans rien
 * déclencher : un vrai balayage (GPS activé au franchissement) n'ouvre
 * jamais le micro. L'appui long normal ne peut pas coexister avec une
 * attente ici : il s'est déjà annulé au-delà de son slop quand le
 * balayage est confirmé (80 px >> 24 px).
 */
class SwipeHoldTrigger(
    private val scheduler: LongPressTrigger.DelayScheduler,
    private val listener: Listener,
    private val holdMs: Long = LongPressTrigger.DEFAULT_LONG_PRESS_MS,
    private val slopPx: Float = LongPressTrigger.DEFAULT_SLOP_PX,
) {

    /** Rappels d'état, émis sur le thread du scheduler (main thread dans l'app). */
    interface Listener {
        /** Seuil atteint : ouvrir le micro destination. */
        fun onSwipeHoldStart()

        /** Tenue terminée (relâchement, annulation, doigt supplémentaire). */
        fun onSwipeHoldEnd()
    }

    enum class Phase { Idle, Pending, Holding }

    var phase: Phase = Phase.Idle
        private set

    val holding: Boolean get() = phase == Phase.Holding

    private var cancelTimer: (() -> Unit)? = null
    private var holdX = 0f
    private var holdY = 0f

    /**
     * Balayage vers le bas confirmé en ([x], [y]) : arme le maintien.
     * Ignoré si une tenue est déjà en cours (elle garde la main).
     */
    fun arm(x: Float, y: Float) {
        if (phase == Phase.Holding) return
        reset()
        holdX = x
        holdY = y
        phase = Phase.Pending
        cancelTimer = scheduler.schedule(holdMs) {
            if (phase == Phase.Pending) {
                phase = Phase.Holding
                listener.onSwipeHoldStart()
            }
        }
    }

    /** Glissement : au-delà du seuil pendant l'attente, le maintien est abandonné. */
    fun onMove(x: Float, y: Float) {
        if (phase == Phase.Pending && hypot(x - holdX, y - holdY) > slopPx) reset()
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
        listener.onSwipeHoldEnd()
    }
}
