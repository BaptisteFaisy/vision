package dev.sam3vision.core.gesture

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Machine à gestes iSee, sans dépendance Android (testable en JVM) : tap simple,
 * double tap et balayage horizontal (reconnu, sans effet : iSee n'a qu'une page).
 * L'appui long reste le domaine de [LongPressTrigger] — dans la vue : quand une
 * tenue démarre, [handOffToHold] glisse tout l'état présent (tap en attente inclus)
 * sous le contrôle de la tenue.
 *
 * Un glissement n'est reconnu qu'une fois par geste : dès qu'un axe gagne le duel
 * (au-delà de [slopPx]), l'autre axe est verrouillé jusqu'au relâchement. Le rail
 * **vertical** n'émet plus rien : le balayage de changement de bulle a été supprimé.
 * Il verrouille seulement le geste — un glissement n'arrive jamais pour un tap
 * (sinon il aurait lancé la vidéo ou la photo).
 */
class IhearGestureBrain(
    private val listener: Listener,
    private val scheduler: DelayScheduler,
    private val slopPx: Float = DEFAULT_SLOP_PX,
    private val doubleTapMs: Long = DEFAULT_DOUBLE_TAP_MS,
    private val horizontalMinPx: Float = DEFAULT_HORIZONTAL_MIN_PX,
) {

    fun interface DelayScheduler {
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
    }

    interface Listener {
        /** Tap court confirmé (second tap absent dans la fenêtre). */
        fun onSingleTap()

        /** Deux taps rapprochés : photo. */
        fun onDoubleTap()

        /** Balayage horizontal confirmé ; `rightToLeft` = doigt parti vers la gauche. */
        fun onHorizontalSwipe(rightToLeft: Boolean)
    }

    private enum class Axis { None, Horizontal, Vertical }

    private class Press(
        val downX: Float,
        val downY: Float,
        val downAt: Long,
    ) {
        var axis: Axis = Axis.None
        var horizontalEmitted: Boolean = false
    }

    private var press: Press? = null
    private var pendingSingle = false
    private var armedDouble = false
    private var lastTapDownX = 0f
    private var lastTapDownY = 0f
    private var lastTapUpAt = 0L
    private var cancelPendingSingle: (() -> Unit)? = null
    private var held = false

    /** Premier doigt posé. */
    @Synchronized
    fun onDown(nowMs: Long, x: Float, y: Float) {
        // Le second tap se juge AVANT l'annulation : pendingTapCancelled() remet
        // pendingSingle à zéro, le calcul lu après lui ne verrait jamais le tap 1.
        val isSecond = pendingSingle &&
            nowMs - lastTapUpAt <= doubleTapMs &&
            hypot(x - lastTapDownX, y - lastTapDownY) <= slopPx * 2
        pendingTapCancelled()
        if (held) return // une tenue est en cours : elle garde la main jusqu'à la relâche
        press = Press(downX = x, downY = y, downAt = nowMs)
        armedDouble = isSecond
    }

    /** Glissement pendant la pression : duel d'axes, puis rails verrouillés. */
    @Synchronized
    fun onMove(nowMs: Long, x: Float, y: Float) {
        val current = press ?: return
        if (held) return
        val dx = x - current.downX
        val dy = y - current.downY
        if (current.axis == Axis.None && hypot(dx, dy) > slopPx) {
            current.axis = if (abs(dx) >= abs(dy)) Axis.Horizontal else Axis.Vertical
            pendingTapCancelled() // un glissement annule le tap en attente
        }
        if (current.axis == Axis.Horizontal && !current.horizontalEmitted && abs(dx) > horizontalMinPx) {
            current.horizontalEmitted = true
            listener.onHorizontalSwipe(rightToLeft = dx < 0)
        }
    }

    /** Relâchement : fin du rail vertical, ou détection tap/double-tap. */
    @Synchronized
    fun onUp(nowMs: Long, x: Float, y: Float) {
        val current = press
        press = null
        if (held) return
        if (current != null) {
            if (current.axis == Axis.Vertical) return // glissement : jamais un tap
            if (current.horizontalEmitted) return
            val quick = nowMs - current.downAt <= doubleTapMs
            if (!quick) return
            if (armedDouble) {
                armedDouble = false
                pendingSingle = false
                pendingTapCancelled()
                listener.onDoubleTap()
            } else {
                pendingSingle = true
                lastTapDownX = current.downX
                lastTapDownY = current.downY
                lastTapUpAt = nowMs
                scheduleSingleTap()
            }
        }
    }

    /** Annulation système : tout est jeté sans rappel. */
    @Synchronized
    fun onCancel() {
        press = null
        pendingTapCancelled()
    }

    /** La tenue (appui long) prend la main : tap en attente aboli, gestes ignorés. */
    @Synchronized
    fun handOffToHold() {
        held = true
        pendingTapCancelled()
    }

    /** Fin de tenue : reprise de l'écoute aux gestes au prochain [onDown]. */
    @Synchronized
    fun holdReleased() {
        held = false
        press = null
    }

    /** Remise à zéro inconditionnelle (vue détachée, changement d'écran). */
    @Synchronized
    fun reset() {
        press = null
        pendingTapCancelled()
        pendingSingle = false
        armedDouble = false
        held = false
    }

    /** Un seul tap confirmé si aucun second tap n'arrive dans la fenêtre du double tap. */
    private fun scheduleSingleTap() {
        cancelPendingSingle?.invoke()
        cancelPendingSingle = scheduler.schedule(doubleTapMs) {
            cancelPendingSingle = null
            if (pendingSingle) {
                pendingSingle = false
                listener.onSingleTap()
            }
        }
    }

    private fun pendingTapCancelled() {
        cancelPendingSingle?.invoke()
        cancelPendingSingle = null
        pendingSingle = false
        armedDouble = false
    }

    companion object {
        const val DEFAULT_SLOP_PX = 24f
        const val DEFAULT_DOUBLE_TAP_MS = 450L
        const val DEFAULT_HORIZONTAL_MIN_PX = 80f
    }
}
