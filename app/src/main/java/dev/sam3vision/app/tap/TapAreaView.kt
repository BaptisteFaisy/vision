package dev.sam3vision.app.tap

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Surface de tap plein écran. **L'action part au relâchement du doigt** (`ACTION_UP`), pas à
 * la confirmation du tap : `GestureDetector.onSingleTapConfirmed` attend le délai du double
 * tap (~370 ms) et, sur deux taps rapides, ne se déclenche jamais — le second tap était avalé.
 *
 * Un tap court = un `UP` dont
 * - la durée (`eventTime - downTime`) reste sous le seuil appui long par défaut
 *   ([ViewConfiguration.getLongPressTimeout], ~500 ms),
 * - et qui n'a pas dépassé le seuil de glissement par défaut ([ViewConfiguration.getScaledTouchSlop]).
 * L'appui long et le glissement sont détectés par [GestureDetector] (seuils par défaut) et
 * marquent la séquence : leur `UP` ne déclenche rien.
 *
 * Les bandes des barres système (retour/accueil/récents) sont ignorées deux fois : le conteneur
 * Compose est paddé par `WindowInsets`, et [isExcludedTapZone] rejette tout point qui y tomberait.
 * TalkBack : `performClick()` = même action, une seule fois.
 */
class TapAreaView(
    context: Context,
    private val onShortTap: () -> Unit,
    private val longPressAction: (() -> Unit)? = null,
) : View(context) {

    private val longPressTimeoutMs: Long = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlopPx: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var topInsetPx = 0
    private var bottomInsetPx = 0

    private var downAt = 0L
    private var longPressed = false
    private var dragged = false

    /** Drapeaux posés par GestureDetector : c'est lui qui connaît les seuils par défaut. */
    private val listener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            longPressed = true
            longPressAction?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            dragged = true
            return true
        }
    }

    private val detector = GestureDetector(context, listener)

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Recording screen: tap anywhere to film, " +
            "tap again to send"
    }

    /** Insets des barres système, en pixels : un tap dans ces bandes ne déclenche jamais la vidéo. */
    fun setSystemInsets(topPx: Int, bottomPx: Int) {
        topInsetPx = topPx
        bottomInsetPx = bottomPx
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isExcludedTapZone(event.y, topInsetPx, bottomInsetPx, height)) return false
        if (isExcludedSideZone(event.x, touchSlopPx, width)) return false
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPress(event)
            MotionEvent.ACTION_UP -> finishPress(event)
            MotionEvent.ACTION_CANCEL -> resetPress()
        }
        return true
    }

    /** Le relâchement d'un tap court déclenche l'action, immédiatement. */
    private fun finishPress(event: MotionEvent) {
        val isShortTap = isShortTapRelease(longPressed, dragged, event.eventTime - downAt, longPressTimeoutMs)
        resetPress()
        if (isShortTap) performClick()
    }

    private fun startPress(event: MotionEvent) {
        downAt = event.eventTime
        longPressed = false
        dragged = false
    }

    private fun resetPress() {
        downAt = 0L
        longPressed = false
        dragged = false
    }

    /** Action TalkBack / clic : un tap court, une seule fois. */
    override fun performClick(): Boolean {
        super.performClick()
        onShortTap()
        return true
    }
}
