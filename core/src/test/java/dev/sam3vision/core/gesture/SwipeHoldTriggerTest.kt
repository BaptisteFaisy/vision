package dev.sam3vision.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slide vers le bas confirmé puis maintien : seuil de 300 ms, glissement repris annule, clôture unique. */
class SwipeHoldTriggerTest {

    private class FakeScheduler : LongPressTrigger.DelayScheduler {
        var pending: (() -> Unit)? = null
        var delayMs = -1L
        var cancelled = 0

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            this.delayMs = delayMs
            pending = action
            return {
                cancelled++
                pending = null
            }
        }

        fun elapse() {
            val action = pending
            pending = null
            action?.invoke()
        }
    }

    private class Recorder : SwipeHoldTrigger.Listener {
        val events = mutableListOf<String>()
        override fun onSwipeHoldStart() { events += "start" }
        override fun onSwipeHoldEnd() { events += "end" }
    }

    private val scheduler = FakeScheduler()
    private val recorder = Recorder()
    private val trigger = SwipeHoldTrigger(scheduler, recorder)

    @Test
    fun `le swipe confirme arme un delai de 300 ms`() {
        trigger.arm(10f, 90f)
        assertEquals(300L, scheduler.delayMs)
        assertEquals(SwipeHoldTrigger.Phase.Pending, trigger.phase)
    }

    @Test
    fun `maintien apres swipe demarre une seule fois`() {
        trigger.arm(10f, 90f)
        scheduler.elapse()
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
        assertTrue(trigger.holding)
    }

    @Test
    fun `relachement avant le seuil ne declenche rien`() {
        trigger.arm(10f, 90f)
        trigger.onUp()
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
        assertEquals(SwipeHoldTrigger.Phase.Idle, trigger.phase)
    }

    @Test
    fun `glissement repris avant le seuil annule`() {
        trigger.arm(10f, 90f)
        trigger.onMove(10f, 200f)
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
        assertFalse(trigger.holding)
    }

    @Test
    fun `petit tremblement tolere`() {
        trigger.arm(10f, 90f)
        trigger.onMove(12f, 92f)
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
    }

    @Test
    fun `second doigt avant le seuil annule`() {
        trigger.arm(10f, 90f)
        trigger.onPointerDown()
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun `relachement pendant la tenue clot une seule fois`() {
        trigger.arm(10f, 90f)
        scheduler.elapse()
        trigger.onUp()
        trigger.onUp()
        assertEquals(listOf("start", "end"), recorder.events)
        assertEquals(SwipeHoldTrigger.Phase.Idle, trigger.phase)
    }

    @Test
    fun `cancel pendant la tenue clot la tenue`() {
        trigger.arm(10f, 90f)
        scheduler.elapse()
        trigger.onCancel()
        assertEquals(listOf("start", "end"), recorder.events)
    }

    @Test
    fun `second doigt pendant la tenue clot la tenue`() {
        trigger.arm(10f, 90f)
        scheduler.elapse()
        trigger.onPointerDown()
        assertEquals(listOf("start", "end"), recorder.events)
    }

    @Test
    fun `un nouveau swipe repart proprement`() {
        trigger.arm(10f, 90f)
        scheduler.elapse()
        trigger.onUp()
        trigger.arm(10f, 120f)
        scheduler.elapse()
        trigger.onUp()
        assertEquals(listOf("start", "end", "start", "end"), recorder.events)
        assertTrue(scheduler.cancelled >= 2)
    }
}
