package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grâce d'extinction GPU : un aller-retour rapide (< 60 s) retrouve la carte
 * chaude, un fond sans retour l'éteint une fois — jamais prématuré, jamais sur
 * carte déjà retombée. Ordonnanceur et horloge injectés : aucun test ne dort.
 */
class IseeReleaseGraceTest {

    /** Ordonnanceur manuel : `fire` joue le tir, `cancelled` prouve l'annulation. */
    private class FakeSchedule {
        var task: (() -> Unit)? = null
        var delayMs: Long = -1
        var cancelled = 0
        val schedule: (Long, () -> Unit) -> Cancellable = { delay, run ->
            delayMs = delay
            task = run
            Cancellable { cancelled += 1; task = null }
        }

        fun fire() {
            task?.invoke()
        }
    }

    private var nowMs = 1_000_000L
    private val clock = { nowMs }

    private fun grace(fake: FakeSchedule) = ReleaseGrace(clock = clock, schedule = fake.schedule)

    @Test
    fun `arm planifie un tir unique à 60 s`() {
        val fake = FakeSchedule()
        val grace = grace(fake)

        grace.arm(extinguish = {})

        assertTrue(grace.armed)
        assertEquals(BACKGROUND_GRACE_MS, fake.delayMs)
    }

    @Test
    fun `le tir éteint une fois à l_échéance`() {
        val fake = FakeSchedule()
        var calls = 0
        val grace = grace(fake)

        grace.arm(extinguish = { calls += 1 })
        nowMs += BACKGROUND_GRACE_MS
        fake.fire()
        fake.fire()

        assertEquals(1, calls)
        assertFalse("un seul tir par bras", grace.armed)
    }

    @Test
    fun `cancel avant l_échéance n_éteint jamais`() {
        val fake = FakeSchedule()
        var calls = 0
        val grace = grace(fake)

        grace.arm(extinguish = { calls += 1 })
        grace.cancel()
        nowMs += BACKGROUND_GRACE_MS
        fake.fire()

        assertEquals(1, fake.cancelled)
        assertEquals(0, calls)
        assertFalse(grace.armed)
    }

    @Test
    fun `le tir prématuré n_éteint pas`() {
        val fake = FakeSchedule()
        var calls = 0
        val grace = grace(fake)

        grace.arm(extinguish = { calls += 1 })
        nowMs += BACKGROUND_GRACE_MS - 1
        fake.fire()

        assertEquals(0, calls)
    }

    @Test
    fun `carte retombée à l_échéance, pas d_extinction`() {
        val fake = FakeSchedule()
        var calls = 0
        val grace = grace(fake)

        grace.arm(shouldRelease = { false }, extinguish = { calls += 1 })
        nowMs += BACKGROUND_GRACE_MS
        fake.fire()

        assertEquals("socket fermé : Modal a déjà réduit, pas de boot-suicide", 0, calls)
    }

    @Test
    fun `réarmer annule le bras précédent`() {
        val fake = FakeSchedule()
        var calls = 0
        val grace = grace(fake)

        grace.arm(extinguish = { calls += 10 })
        grace.arm(extinguish = { calls += 1 })
        nowMs += BACKGROUND_GRACE_MS
        fake.fire()

        assertEquals(1, fake.cancelled)
        assertEquals("seul le dernier bras tire", 1, calls)
    }
}
