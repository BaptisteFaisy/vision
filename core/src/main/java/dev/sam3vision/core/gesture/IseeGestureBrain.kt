package dev.sam3vision.core.gesture

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Machine à gestes iSee, sans dépendance Android (testable en JVM) : tap simple
 * et trois balayages utiles.
 *
 * - Tap simple : émis **dès le relâchement** — sans double tap à attendre, il n'y
 *   a plus de fenêtre de confirmation (le tap répondait 450 ms après le doigt).
 * - Balayage vers le **haut** : [Listener.onSwipeUp] (bascule du mode Traversée).
 * - Balayage vers le **bas** : [Listener.onSwipeDown] (mode GPS).
 * - Balayage vers la **droite** : [Listener.onSwipeRight] (session lunettes si joignable, sinon « quoi chercher ? »).
 *
 * Le balayage vers la gauche est reconnu puis **muet** : iSee n'a qu'une page, il
 * n'y a rien à feuilleter — mais le geste reste un glissement, jamais un tap.
 *
 * L'appui long reste le domaine de [LongPressTrigger] — dans la vue : quand une
 * tenue démarre, [handOffToHold] glisse tout l'état présent sous le contrôle
 * de la tenue.
 *
 * Un glissement n'est reconnu qu'une fois par geste : dès qu'un axe gagne le duel
 * (au-delà de [slopPx]), l'autre axe est verrouillé jusqu'au relâchement, et le
 * balayage part une seule fois, au-delà de sa course minimale dans cet axe.
 */
class IseeGestureBrain(
    private val listener: Listener,
    private val slopPx: Float = DEFAULT_SLOP_PX,
    private val tapMaxMs: Long = DEFAULT_TAP_MAX_MS,
    private val horizontalMinPx: Float = DEFAULT_HORIZONTAL_MIN_PX,
    private val verticalMinPx: Float = DEFAULT_VERTICAL_MIN_PX,
) {

    interface Listener {
        /** Tap court, émis dès le relâchement. */
        fun onSingleTap()

        /** Balayage vers le haut confirmé (bascule du mode Traversée). */
        fun onSwipeUp()

        /** Balayage vers le bas confirmé (mode GPS). */
        fun onSwipeDown()

        /** Balayage vers la droite confirmé (iSee demande l'objet à chercher). */
        fun onSwipeRight()
    }

    private enum class Axis { None, Horizontal, Vertical }

    private class Press(
        val downX: Float,
        val downY: Float,
        val downAt: Long,
    ) {
        var axis: Axis = Axis.None

        /** Le balayage de ce geste a déjà été jugé (parti, ou sans effet). */
        var swipeEmitted: Boolean = false
    }

    private var press: Press? = null
    private var held = false

    /** Premier doigt posé. */
    @Synchronized
    fun onDown(nowMs: Long, x: Float, y: Float) {
        if (held) return // une tenue est en cours : elle garde la main jusqu'à la relâche
        press = Press(downX = x, downY = y, downAt = nowMs)
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
        }
        if (current.swipeEmitted) return
        if (current.axis == Axis.Horizontal && abs(dx) > horizontalMinPx) {
            current.swipeEmitted = true
            if (dx > 0) listener.onSwipeRight()
        }
        if (current.axis == Axis.Vertical && abs(dy) > verticalMinPx) {
            current.swipeEmitted = true
            if (dy < 0) listener.onSwipeUp() else listener.onSwipeDown()
        }
    }

    /** Relâchement : un glissement n'est jamais un tap, sinon tap immédiat. */
    @Synchronized
    fun onUp(nowMs: Long, x: Float, y: Float) {
        val current = press
        press = null
        if (held) return
        if (current != null) {
            if (current.axis == Axis.Vertical) return // glissement : jamais un tap
            if (current.swipeEmitted) return
            if (nowMs - current.downAt <= tapMaxMs) listener.onSingleTap()
        }
    }

    /** Annulation système : tout est jeté sans rappel. */
    @Synchronized
    fun onCancel() {
        press = null
    }

    /** La tenue (appui long) prend la main : gestes ignorés jusqu'à la relâche. */
    @Synchronized
    fun handOffToHold() {
        held = true
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
        held = false
    }

    companion object {
        const val DEFAULT_SLOP_PX = 24f

        /** Durée maximale d'une pression pour valoir tap (au-delà : rien). */
        const val DEFAULT_TAP_MAX_MS = 450L

        /** Course horizontale pour valoir balayage (droite seule a un effet). */
        const val DEFAULT_HORIZONTAL_MIN_PX = 80f

        /** Course verticale pour valoir bascule : haut = Traversée, bas = GPS. */
        const val DEFAULT_VERTICAL_MIN_PX = 80f
    }
}
