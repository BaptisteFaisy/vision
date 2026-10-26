package dev.sam3vision.app

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import dev.sam3vision.core.gesture.IhearGestureBrain
import dev.sam3vision.core.gesture.LongPressTrigger

/**
 * Racine de la fenêtre iSee : la seule vue qui voit **tous** les gestes de la surface
 * de l'app (l'unique page chat, textures et bulles confondues). Deux machines y tournent :
 *
 * - [LongPressTrigger] : appui maintenu ~300 ms → [onHoldStart] (micro ouvert) ; la
 *   vue intercepte alors tout (les enfants reçoivent un ACTION_CANCEL) jusqu'au
 *   relâchement qui émet [onHoldEnd] ;
 * - [IhearGestureBrain] : tap simple (vidéo), double tap (photo) et balayage vertical
 *   (sélection de bulle). L'ancien balayage horizontal n'est plus écouté : iSee
 *   n'a plus qu'une page.
 *
 * Les taps ne sont jamais consommés en-dehors d'une tenue : ce sont des observations
 * posées sur les événements, les écrans iSee restent libres de leur rendu. Les deux
 * bords latéraux restent exclus du geste système « retour » ; en revanche les balayages
 * haut/bas appartiennent à l'app : la page est alimentée sur **toute** la surface, bande
 * du bas comprise — quand la navigation gestuelle d'Android revendique d'elle-même son
 * geste « Home », le toucher n'arrive jamais jusqu'ici et la bulle n'est pas avancée.
 */
class IhearRootLayout(context: Context) : FrameLayout(context) {

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    /** Tenue annulée sans transcription (app passée en arrière-plan pendant l'appui). */
    var onHoldCancel: (() -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onVerticalDragStart: (() -> Unit)? = null
    var onVerticalSwipeEnd: ((committed: Boolean, downward: Boolean) -> Unit)? = null

    /** Marche décidée dès le début du glissement vertical : une bulle par geste. */
    var onVerticalDragMove: ((downward: Boolean) -> Unit)? = null

    /** Geste actif seulement quand les panneaux iSee sont à l'écran, sinon à l'ancienne UI. */
    var gesturesEnabled: () -> Boolean = { true }

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false
    private var draggingVertical = false

    private val trigger = LongPressTrigger(
        scheduler = ::scheduleDelayed,
        listener = object : LongPressTrigger.Listener {
            override fun onHoldStart() {
                capturing = true
                brain.handOffToHold()
                onHoldStart?.invoke()
            }

            override fun onHoldEnd() {
                capturing = false
                brain.holdReleased()
                onHoldEnd?.invoke()
            }
        },
    )

    private val brain = IhearGestureBrain(
        listener = object : IhearGestureBrain.Listener {
            override fun onSingleTap() = onSingleTap?.invoke() ?: Unit
            override fun onDoubleTap() = onDoubleTap?.invoke() ?: Unit
            override fun onHorizontalSwipe(rightToLeft: Boolean) = Unit // aucune page à changer
            override fun onVerticalDrag(downward: Boolean) {
                draggingVertical = true
                onVerticalDragStart?.invoke()
                onVerticalDragMove?.invoke(downward)
            }

            override fun onVerticalSwipe(committed: Boolean, downward: Boolean) {
                draggingVertical = false
                onVerticalSwipeEnd?.invoke(committed, downward)
            }
        },
        scheduler = ::scheduleDelayed,
    )

    /** Reporte [action] sur le thread principal ; rend l'annulation. */
    private fun scheduleDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return {
            handler.removeCallbacks(runnable)
            Unit
        }
    }

    /**
     * iHear n'a **aucun** contrôle tactile (ni bouton, ni champ) : quand ses panneaux sont
     * à l'écran, la racine prend le geste dès le DOWN. Sans ça, un balayage non consommé
     * remontait au système, qui ouvrait le volet de notifications au milieu d'un swipe
     * (mesuré sur le téléphone). Hors iHear, rien n'est intercepté : Réglages garde ses
     * boutons.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = gesturesEnabled()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled()) return super.onTouchEvent(ev)
        if (capturing) feedHeld(ev) else feed(ev)
        return true
    }

    /**
     * Annule le geste en cours (app en arrière-plan au milieu d'un appui long) : la tenue
     * est jetée sans transcription et l'haptique est coupée, sinon l'écran restait sur
     * « Listening… » avec le micro ouvert.
     */
    fun cancelGesture() {
        trigger.reset()
        brain.reset()
        if (draggingVertical) {
            draggingVertical = false
            onVerticalSwipeEnd?.invoke(false, false)
        }
        if (capturing) {
            capturing = false
            onHoldCancel?.invoke()
        }
    }

    /** Pendant une tenue : l'appui long fait tout arrêt/relance sur relâchement. */
    private fun feedHeld(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> trigger.onUp()
            else -> Unit
        }
    }

    /** Tous les gestes de la surface passent ici avant de descendre aux enfants. */
    private fun feed(ev: MotionEvent) {
        if (!gesturesEnabled()) {
            trigger.reset()
            if (draggingVertical) {
                draggingVertical = false
                onVerticalSwipeEnd?.invoke(false, false)
                return
            }
            return
        }
        val now = System.currentTimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trigger.onDown(ev.x, ev.y)
                brain.onDown(now, ev.x, ev.y)
            }

            MotionEvent.ACTION_MOVE -> {
                trigger.onMove(ev.x, ev.y)
                brain.onMove(now, ev.x, ev.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                trigger.onPointerDown()
                brain.onCancel()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trigger.onUp()
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) brain.onCancel() else brain.onUp(now, ev.x, ev.y)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) excludeSideGestures()
    }

    /** Les deux bords latéraux sont retirés du geste « retour » du système. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun excludeSideGestures() {
        if (width <= 0 || height <= 0) return
        val dp = resources.displayMetrics.density
        val side = minOf(EXCLUSION_SIDE_DP * dp, 200f * dp).toInt()
        systemGestureExclusionRects = listOf(
            Rect(0, 0, side, height),
            Rect(width - side, 0, width, height),
        )
    }

    override fun onDetachedFromWindow() {
        trigger.reset()
        brain.reset()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Bande latérale exclue du geste système (retour) : moindre que le plafond 200dp. */
        private const val EXCLUSION_SIDE_DP = 48f
    }
}
