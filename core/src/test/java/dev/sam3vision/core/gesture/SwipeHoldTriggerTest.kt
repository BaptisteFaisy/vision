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

    /** Collecte les motifs d'abandon (chaque mort du timer est observable). */
    private class AbandonRecorder : SwipeHoldTrigger.Listener {
        val abandoned = mutableListOf<SwipeHoldTrigger.CancelReason>()
        override fun onSwipeHoldStart() {}
        override fun onSwipeHoldEnd() {}
        override fun onSwipeHoldAbandoned(reason: SwipeHoldTrigger.CancelReason) { abandoned += reason }
    }

    private val scheduler = FakeScheduler()
    private val recorder = Recorder()
    private var nowMs = 0L
    private val trigger = SwipeHoldTrigger(scheduler, recorder, nowMs = { nowMs })

    /** Déclencheur frais branché sur un collecteur d'abandons. */
    private fun abandonSetup(): Triple<FakeScheduler, AbandonRecorder, SwipeHoldTrigger> {
        val sched = FakeScheduler()
        val rec = AbandonRecorder()
        return Triple(sched, rec, SwipeHoldTrigger(sched, rec, nowMs = { nowMs }))
    }

    /** Sort de la grâce post-armement : le jugement des bonds est actif. */
    private fun pastGrace() {
        nowMs = SwipeHoldTrigger.ARM_GRACE_MS
    }

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
        pastGrace()
        trigger.onMove(10f, 200f)
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
        assertFalse(trigger.holding)
    }

    @Test
    fun `derive lente apres swipe garde le maintien`() {
        trigger.arm(10f, 90f)
        trigger.onMove(10f, 105f)
        trigger.onMove(10f, 120f)
        trigger.onMove(10f, 132f)
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
        assertTrue(trigger.holding)
    }

    @Test
    fun `pas rapides post-armement survivent sous 48px`() {
        trigger.arm(10f, 90f)
        trigger.onMove(10f, 120f)
        trigger.onMove(10f, 155f)
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
        assertTrue(trigger.holding)
    }

    @Test
    fun `bond extreme au-dela de 48px annule toujours`() {
        trigger.arm(10f, 90f)
        pastGrace()
        trigger.onMove(10f, 150f)
        scheduler.elapse()
        assertEquals(emptyList<String>(), recorder.events)
        assertEquals(SwipeHoldTrigger.Phase.Idle, trigger.phase)
    }

    @Test
    fun `glissement repris signale l'annulation`() {
        val events = mutableListOf<String>()
        val watched = SwipeHoldTrigger(scheduler, object : SwipeHoldTrigger.Listener {
            override fun onSwipeHoldStart() { events += "start" }
            override fun onSwipeHoldEnd() { events += "end" }
            override fun onSwipeHoldCancel() { events += "cancel" }
        }, nowMs = { nowMs })
        watched.arm(10f, 90f)
        pastGrace()
        watched.onMove(10f, 200f)
        scheduler.elapse()
        assertEquals(listOf("cancel"), events)
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

    @Test
    fun `bond franc signale JUMP_SLOP`() {
        val (sched, rec, trig) = abandonSetup()
        trig.arm(10f, 90f)
        pastGrace()
        trig.onMove(10f, 200f)
        sched.elapse()
        assertEquals(listOf(SwipeHoldTrigger.CancelReason.JUMP_SLOP), rec.abandoned)
    }

    @Test
    fun `pas rapides dans la grace post-armement gardent le maintien`() {
        trigger.arm(10f, 90f)
        nowMs = 10
        trigger.onMove(10f, 160f)
        nowMs = 25
        trigger.onMove(10f, 240f)
        nowMs = 300
        scheduler.elapse()
        assertEquals(listOf("start"), recorder.events)
        assertTrue(trigger.holding)
    }

    @Test
    fun `bond juste apres la grace signale JUMP_SLOP`() {
        val (sched, rec, trig) = abandonSetup()
        trig.arm(10f, 90f)
        nowMs = 50
        trig.onMove(10f, 120f)
        pastGrace()
        trig.onMove(10f, 250f)
        sched.elapse()
        assertEquals(listOf(SwipeHoldTrigger.CancelReason.JUMP_SLOP), rec.abandoned)
    }

    @Test
    fun `releve avant le seuil signale UP_EARLY`() {
        val (sched, rec, trig) = abandonSetup()
        trig.arm(10f, 90f)
        trig.onUp()
        sched.elapse()
        assertEquals(listOf(SwipeHoldTrigger.CancelReason.UP_EARLY), rec.abandoned)
    }

    @Test
    fun `annulation systeme signale CANCEL_SYSTEM`() {
        val (sched, rec, trig) = abandonSetup()
        trig.arm(10f, 90f)
        trig.onCancel()
        sched.elapse()
        assertEquals(listOf(SwipeHoldTrigger.CancelReason.CANCEL_SYSTEM), rec.abandoned)
    }

    @Test
    fun `second doigt signale SECOND_FINGER`() {
        val (sched, rec, trig) = abandonSetup()
        trig.arm(10f, 90f)
        trig.onPointerDown()
        sched.elapse()
        assertEquals(listOf(SwipeHoldTrigger.CancelReason.SECOND_FINGER), rec.abandoned)
    }

    @Test
    fun `reset externe signale RESET_EXTERNAL`() {
        val (sched, rec, trig) = abandonSetup()
        trig.arm(10f, 90f)
        trig.reset()
        sched.elapse()
        assertEquals(listOf(SwipeHoldTrigger.CancelReason.RESET_EXTERNAL), rec.abandoned)
    }

    @Test
    fun `reset a vide reste muet`() {
        val (_, rec, trig) = abandonSetup()
        trig.reset()
        assertEquals(emptyList<SwipeHoldTrigger.CancelReason>(), rec.abandoned)
    }

    @Test
    fun `compatibilite - l'ancien onSwipeHoldCancel recoit aussi UP_EARLY`() {
        val events = mutableListOf<String>()
        val legacy = SwipeHoldTrigger(scheduler, object : SwipeHoldTrigger.Listener {
            override fun onSwipeHoldStart() { events += "start" }
            override fun onSwipeHoldEnd() { events += "end" }
            override fun onSwipeHoldCancel() { events += "cancel" }
        })
        legacy.arm(10f, 90f)
        legacy.onUp()
        scheduler.elapse()
        assertEquals(listOf("cancel"), events)
    }
}
