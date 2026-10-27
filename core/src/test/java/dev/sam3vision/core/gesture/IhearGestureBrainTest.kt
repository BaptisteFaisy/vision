package dev.sam3vision.core.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Régression du double tap : le second tap doit être reconnu même après le
 * nettoyage d'état de [IhearGestureBrain.onDown] (le calcul se fait avant
 * l'annulation, sinon le double tap disparaît au profit de deux taps simples).
 */
class IhearGestureBrainTest {

    /** Horloge manuelle + planificateur contrôlable pour le tap en attente. */
    private class FakeScheduler : IhearGestureBrain.DelayScheduler {
        val pending = mutableListOf<() -> Unit>()

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            pending += action
            return { pending.remove(action) }
        }

        fun runPending() {
            val actions = pending.toList()
            pending.clear()
            actions.forEach { it() }
        }
    }

    private class Events {
        var single = 0
        var double = 0

        fun listener() = object : IhearGestureBrain.Listener {
            override fun onSingleTap() {
                single++
            }

            override fun onDoubleTap() {
                double++
            }

            override fun onHorizontalSwipe(rightToLeft: Boolean) = Unit
        }
    }

    @Test
    fun `deux taps rapproches declenchent le double tap et pas le tap simple`() {
        val scheduler = FakeScheduler()
        val events = Events()
        val brain = IhearGestureBrain(events.listener(), scheduler, doubleTapMs = 450L)

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)
        brain.onDown(nowMs = 200, x = 104f, y = 102f)
        brain.onUp(nowMs = 250, x = 104f, y = 102f)
        scheduler.runPending()

        assertTrue("double tap attendu", events.double == 1)
        assertTrue("le tap simple ne doit pas partir en double", events.single == 0)
    }

    @Test
    fun `un tap isole part en tap simple apres la fenetre du double tap`() {
        val scheduler = FakeScheduler()
        val events = Events()
        val brain = IhearGestureBrain(events.listener(), scheduler, doubleTapMs = 450L)

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)
        scheduler.runPending()

        assertTrue("tap simple attendu", events.single == 1)
        assertTrue(events.double == 0)
        assertFalse("plus rien en attente", scheduler.pending.isNotEmpty())
    }

    @Test
    fun `deux taps au-dela de la fenetre donnent deux taps simples`() {
        val scheduler = FakeScheduler()
        val events = Events()
        val brain = IhearGestureBrain(events.listener(), scheduler, doubleTapMs = 450L)

        brain.onDown(nowMs = 0, x = 100f, y = 100f)
        brain.onUp(nowMs = 60, x = 100f, y = 100f)
        scheduler.runPending() // tap 1 confirmé
        brain.onDown(nowMs = 1_000, x = 100f, y = 100f)
        brain.onUp(nowMs = 1_060, x = 100f, y = 100f)
        scheduler.runPending() // tap 2 confirmé

        assertTrue(events.single == 2)
        assertTrue(events.double == 0)
    }
}
