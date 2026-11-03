package dev.sam3vision.core.gesture

import kotlin.math.hypot

/**
 * Machine à états du « slide vers le bas + maintien », sans dépendance
 * Android (testable en JVM). Le balayage vers le bas confirmé l'arme
 * ([arm], position du doigt à la confirmation) ; si le doigt reste posé
 * [holdMs] sans bondir de plus de [slopPx] entre deux pas,
 * [Listener.onSwipeHoldStart] ouvre le micro destination — le relâchement
 * clôt par [Listener.onSwipeHoldEnd] (envoi). Même durée que l'appui long
 * ([LongPressTrigger]), slop doublé ([HOLD_SLOP_PX]) : le doigt est en
 * mouvement à l'armement (80 px de course), pas immobile sur un DOWN.
 *
 * Un bond franc (> slop entre deux MOVE), un relâchement avant le seuil,
 * une annulation système, un doigt supplémentaire ou un [reset] externe
 * abandonnent l'attente en signalant [Listener.onSwipeHoldAbandoned] avec
 * son [CancelReason] — un balayage vite relâché garde son seul effet GPS,
 * mais sa mort est observable en logcat et en test. La dérive lente (le
 * doigt finit son slide en décélérant) déplace l'ancre et garde le
 * maintien. L'appui long normal ne peut pas coexister avec une attente
 * ici : il s'est déjà annulé au-delà de son slop quand le balayage est
 * confirmé (80 px >> 48 px).
 */
class SwipeHoldTrigger(
    private val scheduler: LongPressTrigger.DelayScheduler,
    private val listener: Listener,
    private val holdMs: Long = LongPressTrigger.DEFAULT_LONG_PRESS_MS,
    private val slopPx: Float = HOLD_SLOP_PX,
) {

    /** Rappels d'état, émis sur le thread du scheduler (main thread dans l'app). */
    interface Listener {
        /** Seuil atteint : ouvrir le micro destination. */
        fun onSwipeHoldStart()

        /** Tenue terminée (relâchement, annulation, doigt supplémentaire). */
        fun onSwipeHoldEnd()

        /**
         * Attente abandonnée sans start, avec son motif : chaque mort du
         * timer est observable (logcat) et testable. Par défaut, délègue à
         * [onSwipeHoldCancel] pour les écouteurs historiques.
         */
        fun onSwipeHoldAbandoned(reason: CancelReason) = onSwipeHoldCancel()

        /**
         * Attente abandonnée (historique, sans motif — glissement repris).
         * Gardé pour compatibilité ; préférer [onSwipeHoldAbandoned].
         */
        fun onSwipeHoldCancel() {}
    }

    /**
     * Pourquoi une attente est morte sans start : chaque valeur est une
     * sortie observable du chemin balayage vers le bas + maintien.
     */
    enum class CancelReason {
        /** Bond franc au-delà du slop entre deux MOVE (glissement repris). */
        JUMP_SLOP,
        /** Doigt relevé avant le seuil (le balayage garde son effet GPS). */
        UP_EARLY,
        /** Annulation système (ACTION_CANCEL) avant le seuil. */
        CANCEL_SYSTEM,
        /** Second doigt posé pendant l'attente. */
        SECOND_FINGER,
        /** [reset] externe pendant l'attente (vue détachée, fond, gestes coupés). */
        RESET_EXTERNAL,
    }

    enum class Phase { Idle, Pending, Holding }

    companion object {
        /** Tag logcat commun du chemin maintien GPS (layout + ordres). */
        const val LOG_TAG = "IseeSwipeHold"

        /**
         * Tolérance post-swipe : le doigt file encore à l'armement, et un
         * événement livré peut couvrir plusieurs échantillons batchés — 2×
         * le tremblement statique. Un vrai fling relâche avant 300 ms
         * ([CancelReason.UP_EARLY], GPS seul), pas besoin du 24 px.
         */
        const val HOLD_SLOP_PX = 48f
    }

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
        resetSilent()
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

    /**
     * Glissement : un bond franc au-delà du seuil abandonne l'attente
     * ([CancelReason.JUMP_SLOP]) ; la dérive lente déplace l'ancre et
     * garde le maintien — le doigt finit son slide en décélérant.
     */
    fun onMove(x: Float, y: Float) {
        if (phase != Phase.Pending) return
        if (hypot(x - holdX, y - holdY) > slopPx) {
            abandon(CancelReason.JUMP_SLOP)
        } else {
            holdX = x
            holdY = y
        }
    }

    /** Doigt supplémentaire : abandonne l'attente, ou clôt la tenue en cours. */
    fun onPointerDown() {
        when (phase) {
            Phase.Pending -> abandon(CancelReason.SECOND_FINGER)
            Phase.Holding -> end()
            Phase.Idle -> Unit
        }
    }

    /** Relâchement : clôt la tenue, ou abandonne l'attente ([CancelReason.UP_EARLY]). */
    fun onUp() {
        when (phase) {
            Phase.Holding -> end()
            Phase.Pending -> abandon(CancelReason.UP_EARLY)
            Phase.Idle -> Unit
        }
    }

    /**
     * Annulation système : clôt la tenue, ou abandonne l'attente
     * ([CancelReason.CANCEL_SYSTEM]) — distinguée du relâchement volontaire.
     */
    fun onCancel() {
        when (phase) {
            Phase.Holding -> end()
            Phase.Pending -> abandon(CancelReason.CANCEL_SYSTEM)
            Phase.Idle -> Unit
        }
    }

    /**
     * Remise à zéro inconditionnelle (vue détachée, arrêt de l'app) : une
     * attente interrompue signale [CancelReason.RESET_EXTERNAL], sinon muet.
     */
    fun reset() {
        val wasPending = phase == Phase.Pending
        resetSilent()
        if (wasPending) listener.onSwipeHoldAbandoned(CancelReason.RESET_EXTERNAL)
    }

    /** Abandonne l'attente en signalant son motif (toujours depuis Pending). */
    private fun abandon(reason: CancelReason) {
        resetSilent()
        listener.onSwipeHoldAbandoned(reason)
    }

    /** Coupe le timer et revient à Idle, sans rien signaler. */
    private fun resetSilent() {
        cancelTimer?.invoke()
        cancelTimer = null
        phase = Phase.Idle
    }

    private fun end() {
        resetSilent()
        listener.onSwipeHoldEnd()
    }
}
