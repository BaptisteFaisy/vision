package dev.sam3vision.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le tap simple part **dès le relâchement** : sans double tap à attendre, il n'y
 * a plus de fenêtre de confirmation. Deux taps rapprochés valent deux taps
 * simples ; une pression trop longue ne vaut rien ; un glissement verrouille le
 * geste — seul le balayage vers le haut émet (bascule Traversée) ; la tenue
 * garde la main.
 */
class IseeGestureBrainTest {

    private class Events {
        var single = 0
        var horizontal = 0
        var up = 0

        fun listener() = object : IseeGestureBrain.Listener {
            override fun onSingleTap() {
                single++
            }

            override fun onHorizontalSwipe(rightToLeft: Boolean) {
                horizontal++
            }

            override fun onSwipeUp() {
                up++
            }
        }
    }

    @Test
    fun `un tap part des le relachement, sans attendre`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener(), tapMaxMs = 450L)

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)

        assertEquals("tap immédiat", 1, events.single)
    }

    @Test
    fun `deux taps rapproches donnent deux taps simples`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener(), tapMaxMs = 450L)

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)
        brain.onDown(nowMs = 200, x = 104f, y = 102f)
        brain.onUp(nowMs = 250, x = 104f, y = 102f)

        assertEquals(2, events.single)
    }

    @Test
    fun `une pression trop longue ne vaut pas tap`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener(), tapMaxMs = 450L)

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 600, x = 100f, y = 100f)

        assertEquals("rien", 0, events.single)
    }

    @Test
    fun `un balayage vers le bas n'est pas un tap et n'emet rien`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onMove(nowMs = 30, x = 100f, y = 200f)
        brain.onUp(nowMs = 60, x = 100f, y = 200f)

        assertEquals("pas de tap", 0, events.single)
        assertEquals("rien d'horizontal", 0, events.horizontal)
        assertEquals("pas de swipe-up", 0, events.up)
    }

    @Test
    fun `un balayage vers le haut emet swipe-up une fois, sans tap`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 300f)
        brain.onMove(nowMs = 30, x = 100f, y = 200f)
        brain.onMove(nowMs = 60, x = 100f, y = 120f)
        brain.onUp(nowMs = 90, x = 100f, y = 120f)

        assertEquals("un seul swipe-up", 1, events.up)
        assertEquals("pas de tap", 0, events.single)
        assertEquals("rien d'horizontal", 0, events.horizontal)
    }

    @Test
    fun `un petit glissement vertical n'est ni tap ni swipe-up`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 300f)
        brain.onMove(nowMs = 30, x = 100f, y = 260f)
        brain.onUp(nowMs = 60, x = 100f, y = 260f)

        assertEquals("pas de swipe-up", 0, events.up)
        assertEquals("pas de tap", 0, events.single)
    }

    @Test
    fun `pendant une tenue, le swipe haut est ignore`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.handOffToHold()
        brain.onDown(nowMs = 0, x = 100f, y = 300f)
        brain.onMove(nowMs = 30, x = 100f, y = 150f)
        brain.onUp(nowMs = 60, x = 100f, y = 150f)
        brain.holdReleased()

        assertEquals("rien pendant la tenue", 0, events.up)
    }

    @Test
    fun `pendant une tenue, les taps sont ignores`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.handOffToHold()
        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)
        brain.holdReleased()
        brain.onDown(nowMs = 500, x = 100f, y = 100f)
        brain.onUp(nowMs = 560, x = 100f, y = 100f)

        assertEquals("seul le tap d'après la tenue compte", 1, events.single)
    }
}
