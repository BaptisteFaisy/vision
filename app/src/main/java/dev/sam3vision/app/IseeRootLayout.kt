package dev.sam3vision.app

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import dev.sam3vision.core.gesture.IseeGestureBrain
import dev.sam3vision.core.gesture.LongPressTrigger
import dev.sam3vision.core.gesture.SwipeHoldTrigger

/**
 * Racine de la fenêtre iSee : la seule vue qui voit **tous** les gestes de la surface
 * de l'app (l'unique page chat, textures et bulles confondues). Trois machines y tournent :
 *
 * - [LongPressTrigger] : appui maintenu ~300 ms → [onHoldStart] (micro ouvert) ; la
 *   vue intercepte alors tout (les enfants reçoivent un ACTION_CANCEL) jusqu'au
 *   relâchement qui émet [onHoldEnd] ;
 * - [IseeGestureBrain] : tap simple (vidéo, dès le relâchement), balayage vers
 *   le haut (bascule Traversée), vers le bas (mode GPS), vers la droite
 *   (création d'un picto) et vers la gauche (connexion des lunettes). Ni
 *   double tap, ni page à feuilleter : iSee n'a qu'une page ni qu'une bulle
 *   à parcourir ;
 * - [SwipeHoldTrigger] : balayage vers le bas confirmé puis doigt maintenu
 *   ~300 ms → [onSwipeHoldStart] (micro destination) jusqu'au relâchement qui
 *   émet [onSwipeHoldEnd] (envoi). Un balayage relâché vite garde son seul
 *   effet GPS, sans micro.
 *
 * Les taps ne sont jamais consommés en-dehors d'une tenue : ce sont des observations
 * posées sur les événements, les écrans iSee restent libres de leur rendu. Les deux
 * bords latéraux restent exclus du geste système « retour » ; le balayage vers le
 * haut bascule le mode Traversée, vers le bas la fonction GPS, vers la droite
 * la création d'un picto et vers la gauche la connexion des lunettes.
 */
class IseeRootLayout(context: Context) : FrameLayout(context) {

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    /** Tenue annulée sans transcription (app passée en arrière-plan pendant l'appui). */
    var onHoldCancel: (() -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null

    /** Balayage vers le haut confirmé : bascule du mode Traversée. */
    var onSwipeUp: (() -> Unit)? = null

    /** Balayage vers le bas confirmé : active la fonction GPS (ordre « active le GPS »). */
    var onSwipeDown: (() -> Unit)? = null

    /** Balayage vers la droite confirmé : création d'un picto (comme le bouton). */
    var onSwipeRight: (() -> Unit)? = null

    /** Balayage vers la gauche confirmé : finit de connecter les lunettes si joignables, sinon rien. */
    var onSwipeLeft: (() -> Unit)? = null

    /** Maintien après balayage vers le bas : ouvrir le micro destination. */
    var onSwipeHoldStart: (() -> Unit)? = null

    /** Relâchement du maintien : couper le micro et envoyer la destination. */
    var onSwipeHoldEnd: (() -> Unit)? = null

    /** Maintien annulé sans transcription (app passée en arrière-plan pendant le maintien). */
    var onSwipeHoldCancel: (() -> Unit)? = null

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

    /**
     * Zone tactile de la puce lieu, mêmes règles que [pictoZone] : un DOWN
     * dedans descend au chip au lieu de toggler la vidéo.
     */
    var placeModeZone: (() -> RectF?)? = null

    /**
     * Zones tactiles des boutons navigation de l'accueil (Démarrer, Annuler,
     * Arrêter, saisie clavier), mêmes règles que [pictoZone] : un DOWN dedans
     * descend au bouton — sans ça, la confirmation et l'arrêt sur l'accueil
     * seraient intouchables (tout partirait en tenue micro ou en vidéo).
     */
    var navZones: (() -> List<RectF>)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false
    private var swipeCapturing = false

    /** Dernière position vue (DOWN/MOVE) : le maintien s'arme dessus à la confirmation du balayage. */
    private var lastX = 0f
    private var lastY = 0f

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

    private val swipeHold = SwipeHoldTrigger(
        scheduler = ::scheduleDelayed,
        listener = object : SwipeHoldTrigger.Listener {
            override fun onSwipeHoldStart() {
                swipeCapturing = true
                brain.handOffToHold()
                Log.d(TAG, "maintien GPS : micro destination ouvert")
                onSwipeHoldStart?.invoke()
            }

            override fun onSwipeHoldEnd() {
                swipeCapturing = false
                brain.holdReleased()
                onSwipeHoldEnd?.invoke()
            }

            override fun onSwipeHoldAbandoned(reason: SwipeHoldTrigger.CancelReason) {
                Log.d(TAG, "maintien GPS abandonné ($reason)")
            }
        },
    )

    // Type explicite : `swipeHold` (ci-dessus) et le brain se référencent à
    // l'initialisation (armement au swipe-down), l'inférence croisée boucle.
    private val brain: IseeGestureBrain = IseeGestureBrain(
        listener = object : IseeGestureBrain.Listener {
            override fun onSingleTap() = onSingleTap?.invoke() ?: Unit
            override fun onSwipeUp() = onSwipeUp?.invoke() ?: Unit
            override fun onSwipeDown() {
                // Le maintien s'arme sur la position de confirmation : tenu
                // ~300 ms, il ouvre le micro destination ; relâché vite, le
                // balayage garde son seul effet GPS.
                Log.d(TAG, "maintien GPS armé")
                swipeHold.arm(lastX, lastY)
                onSwipeDown?.invoke()
            }
            override fun onSwipeRight() = onSwipeRight?.invoke() ?: Unit
            override fun onSwipeLeft() = onSwipeLeft?.invoke() ?: Unit
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
     * iSee n'a que des contrôles tactiles exemptés (Picto, Traversée, lieu, boutons nav) : hors de leurs
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
    private var placeModePass = false
    private var navPass = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // DOWN dans une zone de chip : il descend au chip — sinon le tap
        // togglerait la vidéo au lieu d'agir (vu sur le téléphone, Picto puis
        // Traversée). Sans zone, interception inchangée.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            pictoPass = inZone(pictoZone?.invoke(), ev)
            crossingPass = inZone(crossingZone?.invoke(), ev)
            placeModePass = inZone(placeModeZone?.invoke(), ev)
            navPass = inZones(navZones?.invoke().orEmpty(), ev)
        }
        if (pictoPass || crossingPass || placeModePass || navPass) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pictoPass = false
                crossingPass = false
                placeModePass = false
                navPass = false
            }
            return false
        }
        return gesturesEnabled()
    }

    /** Vrai si le DOWN tombe dans l'une des zones (liste vide = jamais dedans). */
    private fun inZones(zones: List<RectF>, ev: MotionEvent): Boolean {
        val at = IntArray(2)
        getLocationInWindow(at)
        val x = ev.x + at[0]
        val y = ev.y + at[1]
        return zones.any { it.contains(x, y) }
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
        if (capturing || swipeCapturing) feedHeld(ev) else feed(ev)
        return true
    }

    /**
     * Annule le geste en cours (app en arrière-plan au milieu d'un appui long) : la tenue
     * est jetée sans transcription et l'haptique est coupée, sinon l'écran restait sur
     * « Listening… » avec le micro ouvert.
     */
    fun cancelGesture() {
        trigger.reset()
        swipeHold.reset()
        brain.reset()
        if (capturing) {
            capturing = false
            onHoldCancel?.invoke()
        }
        if (swipeCapturing) {
            swipeCapturing = false
            onSwipeHoldCancel?.invoke()
        }
    }

    /** Pendant une tenue : l'appui long fait tout arrêt/relance sur relâchement. */
    private fun feedHeld(ev: MotionEvent) {
        when (ev.actionMasked) {
            // Une seule des deux machines tient à la fois, l'autre est Idle : sans effet sur elle.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { trigger.onUp(); swipeHold.onUp() }
            else -> Unit
        }
    }

    /** Tous les gestes de la surface passent ici avant de descendre aux enfants. */
    private fun feed(ev: MotionEvent) {
        if (!gesturesEnabled()) {
            // Reset silencieux rendu visible, mais seulement s'il tue
            // quelque chose — sinon chaque toucher hors iSee spammerait.
            if (swipeHold.phase != SwipeHoldTrigger.Phase.Idle) {
                Log.d(TAG, "maintien GPS reset (gestes coupés, phase=${swipeHold.phase})")
            }
            trigger.reset()
            swipeHold.reset()
            return
        }
        val now = System.currentTimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                trigger.onDown(ev.x, ev.y)
                brain.onDown(now, ev.x, ev.y)
            }

            MotionEvent.ACTION_MOVE -> {
                // Les MOVE arrivent batchés (coalescés au vsync / à la
                // charge) : rejouer chaque sous-échantillon, sinon le bond
                // inter-événements (doigt rapide) tue le maintien à tort —
                // JUMP_SLOP mesuré sur téléphone à 28 ms post-armement.
                for (i in 0 until ev.historySize) {
                    feedMove(now, ev.getHistoricalX(i), ev.getHistoricalY(i))
                }
                feedMove(now, ev.x, ev.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                trigger.onPointerDown()
                swipeHold.onPointerDown()
                brain.onCancel()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trigger.onUp()
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) swipeHold.onCancel() else swipeHold.onUp()
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) brain.onCancel() else brain.onUp(now, ev.x, ev.y)
            }
        }
    }

    /**
     * Un pas tactile (historique ou live) vers les trois machines, dans
     * l'ordre : position mémorisée avant le brain, dont la confirmation de
     * balayage vers le bas arme le maintien dessus aussitôt.
     */
    private fun feedMove(nowMs: Long, x: Float, y: Float) {
        lastX = x
        lastY = y
        trigger.onMove(x, y)
        brain.onMove(nowMs, x, y)
        swipeHold.onMove(x, y)
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
        swipeHold.reset()
        brain.reset()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Bande latérale exclue du geste système (retour) : moindre que le plafond 200dp. */
        private const val EXCLUSION_SIDE_DP = 48f

        /** Traces du maintien GPS (armement, ouverture, abandon du micro destination). */
        private const val TAG = SwipeHoldTrigger.LOG_TAG
    }
}
