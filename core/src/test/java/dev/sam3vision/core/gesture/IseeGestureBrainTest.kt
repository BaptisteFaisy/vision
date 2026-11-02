package dev.sam3vision.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le tap simple part **dès le relâchement** : sans double tap à attendre, il n'y
 * a plus de fenêtre de confirmation. Deux taps rapprochés valent deux taps
 * simples ; une pression trop longue ne vaut rien ; la tenue garde la main.
 *
 * Balayages : vers le haut (Traversée), vers le bas (GPS) et vers la droite
 * (« quoi chercher ? ») partent une fois ; vers la gauche, rien — mais jamais un
 * tap pour autant.
 */
class IseeGestureBrainTest {

    private class Events {
        var single = 0
        var up = 0
        var down = 0
        var right = 0

        fun listener() = object : IseeGestureBrain.Listener {
            override fun onSingleTap() {
                single++
            }

            override fun onSwipeUp() {
                up++
            }

            override fun onSwipeDown() {
                down++
            }

            override fun onSwipeRight() {
                right++
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
    fun `un balayage vers le haut emet swipe-up une fois, sans tap`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 300f)
        brain.onMove(nowMs = 30, x = 100f, y = 200f)
        brain.onMove(nowMs = 60, x = 100f, y = 120f)
        brain.onUp(nowMs = 90, x = 100f, y = 120f)

        assertEquals("un seul swipe-up", 1, events.up)
        assertEquals("pas de tap", 0, events.single)
        assertEquals("rien vers le bas", 0, events.down)
        assertEquals("rien d'horizontal", 0, events.right)
    }

    @Test
    fun `un balayage vers le bas allume le GPS, une seule fois`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onMove(nowMs = 30, x = 104f, y = 200f)
        brain.onMove(nowMs = 45, x = 108f, y = 260f)
        brain.onUp(nowMs = 60, x = 108f, y = 300f)

        assertEquals("un seul swipe-down", 1, events.down)
        assertEquals("pas de tap", 0, events.single)
        assertEquals("pas de swipe-up", 0, events.up)
        assertEquals("rien à droite", 0, events.right)
    }

    @Test
    fun `un balayage vers la droite demande quoi chercher, une seule fois`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onMove(nowMs = 30, x = 200f, y = 104f)
        brain.onMove(nowMs = 45, x = 260f, y = 108f)
        brain.onUp(nowMs = 60, x = 300f, y = 108f)

        assertEquals("un seul swipe-droite", 1, events.right)
        assertEquals("pas de tap", 0, events.single)
        assertEquals("rien de vertical", 0, events.up + events.down)
    }

    @Test
    fun `un balayage vers la gauche ne fait rien et n'est jamais un tap`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 300f, y = 100f)
        brain.onMove(nowMs = 30, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)

        assertEquals("aucun balayage", 0, events.right + events.up + events.down)
        assertEquals("pas de tap", 0, events.single)
    }

    @Test
    fun `un petit glissement vertical n'est ni tap ni balayage`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 300f)
        brain.onMove(nowMs = 30, x = 100f, y = 260f)
        brain.onUp(nowMs = 60, x = 100f, y = 260f)

        assertEquals("aucun balayage", 0, events.up + events.down)
        assertEquals("pas de tap", 0, events.single)
    }

    @Test
    fun `un petit glissement horizontal reste un tap`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onMove(nowMs = 20, x = 140f, y = 130f)
        brain.onUp(nowMs = 40, x = 140f, y = 130f)

        assertEquals("toujours un tap", 1, events.single)
        assertEquals("aucun balayage", 0, events.up + events.down + events.right)
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
    fun `pendant une tenue, le swipe bas et le swipe droite sont ignores`() {
        val events = Events()
        val brain = IseeGestureBrain(events.listener())

        brain.handOffToHold()
        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onMove(nowMs = 30, x = 100f, y = 250f)
        brain.onUp(nowMs = 60, x = 100f, y = 250f)
        brain.onDown(nowMs = 400, x = 100f, y = 100f)
        brain.onMove(nowMs = 430, x = 250f, y = 100f)
        brain.onUp(nowMs = 460, x = 250f, y = 100f)
        brain.holdReleased()

        assertEquals("rien pendant la tenue", 0, events.down + events.right)
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
