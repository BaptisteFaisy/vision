package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingAnnouncerTest {

    @Test
    fun `le vert parle des la premiere frame`() {
        val announcer = CrossingAnnouncer()
        val cue = announcer.onFrame(0L, CrossingState.Green)
        assertEquals("You can cross", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
        assertEquals(CrossingState.Green, cue?.state)
    }

    @Test
    fun `un vert isole parle aussitot, un rouge isole ne coupe pas`() {
        val announcer = CrossingAnnouncer()
        assertEquals(CrossingState.Green, announcer.onFrame(0L, CrossingState.Green)?.state)
        assertNull(announcer.onFrame(200L, CrossingState.Red))
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
    }

    @Test
    fun `le rouge parle des la deuxieme frame`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Red))
        val cue = announcer.onFrame(200L, CrossingState.Red)
        assertEquals("Don't cross yet", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
    }

    @Test
    fun `le vert clignotant se dit et veto, etat rouge fail-safe`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Red))
        announcer.onFrame(200L, CrossingState.Red) // rouge annoncé
        announcer.onFrame(3000L, CrossingState.Green) // vert annoncé, délai écoulé
        assertNull(announcer.onFrame(3200L, CrossingState.Red))
        announcer.onFrame(3400L, CrossingState.Green)
        val cue = announcer.onFrame(3600L, CrossingState.Red)
        assertEquals("Flashing green, do not cross.", cue?.phrase)
        assertTrue("vert annoncé avant : urgent", cue?.urgent ?: false)
        assertEquals(CrossingState.Red, cue?.state)
        assertNull("veto : pas de traversez tant que ca clignote", announcer.onFrame(3800L, CrossingState.Green))
        assertEquals("Flashing green, do not cross.", announcer.lastCue?.phrase)
    }

    @Test
    fun `le clignotant sur vert annonce coupe comme un rouge`() {
        val announcer = CrossingAnnouncer()
        repeat(3) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
        assertNull(announcer.onFrame(600L, CrossingState.Red))
        announcer.onFrame(800L, CrossingState.Green)
        val cue = announcer.onFrame(1_000L, CrossingState.Red)
        assertEquals("Flashing green, do not cross.", cue?.phrase)
        assertTrue(cue?.urgent ?: false)
    }

    @Test
    fun `le vert se repete toutes les deux secondes`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertEquals("You can cross", announcer.lastCue?.phrase)
        assertNull(announcer.onFrame(1_500L, CrossingState.Green))
        val repeat = announcer.onFrame(2_600L, CrossingState.Green)
        assertEquals("You can cross", repeat?.phrase)
        assertFalse(repeat?.urgent ?: true)
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
        assertNull(announcer.onFrame(3_500L, CrossingState.Green))
        assertEquals("You can cross", announcer.onFrame(4_600L, CrossingState.Green)?.phrase)
    }

    @Test
    fun `le rouge se repete toutes les deux secondes`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Red))
        assertEquals("Don't cross yet", announcer.onFrame(200L, CrossingState.Red)?.phrase)
        assertNull(announcer.onFrame(700L, CrossingState.Red))
        assertNull(announcer.onFrame(1_000L, CrossingState.Red))
        assertNull("pas de répétition à 1 s", announcer.onFrame(1_300L, CrossingState.Red))
        assertNull(announcer.onFrame(1_700L, CrossingState.Red))
        val repeat = announcer.onFrame(2_200L, CrossingState.Red)
        assertEquals("Don't cross yet", repeat?.phrase)
        assertFalse(repeat?.urgent ?: true)
        assertEquals(CrossingState.Red, announcer.lastCue?.state)
        assertNull(announcer.onFrame(3_000L, CrossingState.Red))
        assertEquals("Don't cross yet", announcer.onFrame(4_200L, CrossingState.Red)?.phrase)
    }

    @Test
    fun `la repetition rouge suit le parametre`() {
        val announcer = CrossingAnnouncer(redRepeatMs = 60_000L)
        announcer.onFrame(0L, CrossingState.Red)
        announcer.onFrame(200L, CrossingState.Red)
        assertNull(announcer.onFrame(30_000L, CrossingState.Red))
        assertEquals("Don't cross yet", announcer.onFrame(60_200L, CrossingState.Red)?.phrase)
    }

    @Test
    fun `la repetition verte suit le parametre`() {
        val announcer = CrossingAnnouncer(greenRepeatMs = 60_000L)
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull(announcer.onFrame(30_000L, CrossingState.Green))
        assertEquals("You can cross", announcer.onFrame(60_600L, CrossingState.Green)?.phrase)
    }


    @Test
    fun `le retour au rouge apres un vert coupe tout`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        announcer.onFrame(800L, CrossingState.Red)
        val cue = announcer.onFrame(1_000L, CrossingState.Red)
        assertEquals("Don't cross yet", cue?.phrase)
        assertTrue(cue?.urgent ?: false)
    }

    @Test
    fun `le retour au rouge ignore le delai`() {
        val announcer = CrossingAnnouncer(cooldownMs = 60_000L)
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        announcer.onFrame(800L, CrossingState.Red)
        assertEquals(CrossingState.Red, announcer.onFrame(900L, CrossingState.Red)?.state)
    }

    @Test
    fun `un changement normal respecte le delai`() {
        val announcer = CrossingAnnouncer(cooldownMs = 60_000L)
        announcer.onFrame(0L, CrossingState.Red)
        announcer.onFrame(200L, CrossingState.Red)
        repeat(4) { announcer.onFrame(400L + it * 200L, CrossingState.Green) }
        assertNull(announcer.lastCue?.takeIf { it.state == CrossingState.Green })
    }

    @Test
    fun `reset oublie tout`() {
        val announcer = CrossingAnnouncer()
        announcer.onFrame(0L, CrossingState.Red)
        announcer.onFrame(200L, CrossingState.Red)
        announcer.reset()
        assertNull(announcer.lastCue)
        assertNull(announcer.onFrame(400L, CrossingState.Red))
        assertEquals("Don't cross yet", announcer.onFrame(600L, CrossingState.Red)?.phrase)
    }
}
