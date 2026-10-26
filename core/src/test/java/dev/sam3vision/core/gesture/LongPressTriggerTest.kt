package dev.sam3vision.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seuil de 300 ms, glissement toléré, multi-touch et clôture unique. */
class LongPressTriggerTest {

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

    private class Recorder : LongPressTrigger.Listener {
        val events = mutableListOf<String>()
        override fun onHoldStart() { events += "start" }
        override fun onHoldEnd() { events += "end" }
    }

    private val scheduler = FakeScheduler()
    private val recorder = Recorder()
    private val trigger = LongPressTrigger(scheduler, recorder)

    @Test
    fun `le seuil arme un delai de 300 ms`() {
        trigger.onDown(10f, 10f)
        assertEquals(300L, scheduler.delayMs)
        assertEquals(LongPressTrigger.Phase.Pending, trigger.phase)
    }

    @Test
    fun `appui maintenu demarre une seule fois`() {
        trigger.onDown(10f, 10f)
        scheduler.elapse()
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
        assertTrue(trigger.holding)
    }

    @Test
    fun `relachement avant le seuil ne declenche rien`() {
        trigger.onDown(10f, 10f)
        trigger.onUp()
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
        assertEquals(LongPressTrigger.Phase.Idle, trigger.phase)
    }

    @Test
    fun `glissement avant le seuil annule`() {
        trigger.onDown(10f, 10f)
        trigger.onMove(200f, 10f)
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
        assertFalse(trigger.holding)
    }

    @Test
    fun `petit tremblement tolere`() {
        trigger.onDown(10f, 10f)
        trigger.onMove(12f, 12f)
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
    }

    @Test
    fun `second doigt avant le seuil annule`() {
        trigger.onDown(10f, 10f)
        trigger.onPointerDown()
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun `relachement pendant la tenue clot une seule fois`() {
        trigger.onDown(10f, 10f)
        scheduler.elapse()
        trigger.onUp()
        trigger.onUp()
        assertEquals(listOf("start", "end"), recorder.events)
        assertEquals(LongPressTrigger.Phase.Idle, trigger.phase)
    }

    @Test
    fun `cancel pendant la tenue clot la tenue`() {
        trigger.onDown(10f, 10f)
        scheduler.elapse()
        trigger.onCancel()
        assertEquals(listOf("start", "end"), recorder.events)
    }

    @Test
    fun `second doigt pendant la tenue clot la tenue`() {
        trigger.onDown(10f, 10f)
        scheduler.elapse()
        trigger.onPointerDown()
        assertEquals(listOf("start", "end"), recorder.events)
    }

    @Test
    fun `un nouvel appui repart proprement`() {
        trigger.onDown(10f, 10f)
        scheduler.elapse()
        trigger.onUp()
        trigger.onDown(30f, 30f)
        scheduler.elapse()
        trigger.onUp()
        assertEquals(listOf("start", "end", "start", "end"), recorder.events)
        assertTrue(scheduler.cancelled >= 2)
    }
}