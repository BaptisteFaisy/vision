package dev.sam3vision.app

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import dev.sam3vision.core.gesture.IseeGestureBrain
import dev.sam3vision.core.gesture.LongPressTrigger

/**
 * Racine de la fenêtre iSee : la seule vue qui voit **tous** les gestes de la surface
 * de l'app (l'unique page chat, textures et bulles confondues). Deux machines y tournent :
 *
 * - [LongPressTrigger] : appui maintenu ~300 ms → [onHoldStart] (micro ouvert) ; la
 *   vue intercepte alors tout (les enfants reçoivent un ACTION_CANCEL) jusqu'au
 *   relâchement qui émet [onHoldEnd] ;
 * - [IseeGestureBrain] : tap simple (vidéo, dès le relâchement). Ni double tap,
 *   ni balayage vertical (changement de bulle), ni page horizontale : iSee n'a
 *   plus qu'une page ni qu'une bulle à parcourir.
 *
 * Les taps ne sont jamais consommés en-dehors d'une tenue : ce sont des observations
 * posées sur les événements, les écrans iSee restent libres de leur rendu. Les deux
 * bords latéraux restent exclus du geste système « retour » ; les balayages haut/bas
 * appartiennent au système (navigation Android), l'application ne les utilise plus.
 */
class IseeRootLayout(context: Context) : FrameLayout(context) {

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    /** Tenue annulée sans transcription (app passée en arrière-plan pendant l'appui). */
    var onHoldCancel: (() -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    /** Geste actif seulement quand les panneaux iSee sont à l'écran, sinon à l'ancienne UI. */
    var gesturesEnabled: () -> Boolean = { true }

    /**
     * Zone tactile du bouton Picto, en coordonnées fenêtre (`null` = pas affiché) :
     * un DOWN dedans descend aux enfants au lieu d'être intercepté (voir
     * [onInterceptTouchEvent]). Posée par le chip lui-même, lue à chaque DOWN.
     */
    var pictoZone: (() -> RectF?)? = null

    /**
     * Zone tactile de la puce Traversée, mêmes règles que [pictoZone] : un DOWN
     * dedans descend au chip au lieu de toggler la vidéo.
     */
    var crossingZone: (() -> RectF?)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false

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

    private val brain = IseeGestureBrain(
        listener = object : IseeGestureBrain.Listener {
            override fun onSingleTap() = onSingleTap?.invoke() ?: Unit
            override fun onHorizontalSwipe(rightToLeft: Boolean) = Unit // aucune page à changer
        },
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
     * iSee n'a que deux contrôles tactiles (Picto, Traversée) : hors de leurs
     * zones, quand ses panneaux sont à l'écran, la racine prend le geste dès le
     * DOWN. Sans ça, un balayage non consommé remontait au système, qui ouvrait
     * le volet de notifications au milieu d'un swipe (mesuré sur le téléphone).
     * Hors iSee, rien n'est intercepté : Réglages garde ses boutons.
     */
    /**
     * Geste en cours vers un chip exempté : une fois le DOWN exempté, tout le
     * geste (MOVE, UP) descend aussi — reprendre l'interception au UP enverrait
     * un CANCEL au chip et tuerait le clic (vu sur le téléphone : vidéo coupée,
     * aucune génération). Remis à zéro à chaque fin de geste.
     */
    private var pictoPass = false
    private var crossingPass = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // DOWN dans une zone de chip : il descend au chip — sinon le tap
        // togglerait la vidéo au lieu d'agir (vu sur le téléphone, Picto puis
        // Traversée). Sans zone, interception inchangée.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            pictoPass = inZone(pictoZone?.invoke(), ev)
            crossingPass = inZone(crossingZone?.invoke(), ev)
        }
        if (pictoPass || crossingPass) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pictoPass = false
                crossingPass = false
            }
            return false
        }
        return gesturesEnabled()
    }

    /** Vrai si le DOWN tombe dans la zone (`null` = pas affichée, jamais dedans). */
    private fun inZone(zone: RectF?, ev: MotionEvent): Boolean {
        if (zone == null) return false
        val at = IntArray(2)
        getLocationInWindow(at)
        return zone.contains(ev.x + at[0], ev.y + at[1])
    }

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
