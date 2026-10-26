package dev.sam3vision.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.sam3vision.core.gesture.PressKind
import dev.sam3vision.core.gesture.TapOrHold
import kotlin.math.hypot

/**
 * Tap court sur une zone, classé **au lever du doigt** par [TapOrHold] : durée sous le seuil
 * d'appui long de l'app (300 ms) et glissement sous la tolérance système. Un appui tenu est
 * un appui long — il part à la dictée et ne déclenche jamais le tap, même levé juste après.
 *
 * Rien n'est consommé : posé sous les commandes, il laisse chaque bouton au-dessus garder
 * ses propres taps (Compose livre l'événement au plus haut de la pile).
 */
fun Modifier.tapOnce(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var travel = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            travel = maxOf(travel, hypot(change.position.x - down.position.x, change.position.y - down.position.y))
            if (change.pressed) continue
            val kind = TapOrHold.classify(
                durationMs = change.uptimeMillis - down.uptimeMillis,
                movedPx = travel,
                slopPx = viewConfiguration.touchSlop,
            )
            if (kind == PressKind.TAP) onTap()
            return@awaitEachGesture
        }
    }
}