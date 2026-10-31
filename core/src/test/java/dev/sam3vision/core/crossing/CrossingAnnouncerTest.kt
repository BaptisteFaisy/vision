package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingAnnouncerTest {

    @Test
    fun `le vert exige trois frames sur quatre`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Green))
        assertNull(announcer.onFrame(200L, CrossingState.Green))
        val cue = announcer.onFrame(400L, CrossingState.Green)
        assertEquals("Green, cross now.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
        assertEquals(CrossingState.Green, cue?.state)
    }

    @Test
    fun `le vote tolere une frame rouge intercalee`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Green))
        assertNull(announcer.onFrame(200L, CrossingState.Green))
        assertNull(announcer.onFrame(400L, CrossingState.Red))
        val cue = announcer.onFrame(600L, CrossingState.Green)
        assertEquals("Green, cross now.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
    }

    @Test
    fun `un vert isole ne parle pas`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Green))
        assertNull(announcer.onFrame(200L, CrossingState.Red))
        assertNull(announcer.onFrame(400L, CrossingState.Green))
        assertNull(announcer.lastCue)
    }

    @Test
    fun `le rouge parle des la deuxieme frame`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Red))
        val cue = announcer.onFrame(200L, CrossingState.Red)
        assertEquals("Red, wait.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
    }

    @Test
    fun `le vert clignotant se dit et veto, etat rouge fail-safe`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Green))
        assertNull(announcer.onFrame(200L, CrossingState.Red))
        assertNull(announcer.onFrame(400L, CrossingState.Green))
        val cue = announcer.onFrame(600L, CrossingState.Red)
        assertEquals("Flashing green, do not cross.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
        assertEquals(CrossingState.Red, cue?.state)
        assertNull("veto : pas de traversez tant que ca clignote", announcer.onFrame(800L, CrossingState.Green))
        assertEquals("Flashing green, do not cross.", announcer.lastCue?.phrase)
    }

    @Test
    fun `le clignotant sur vert annonce coupe comme un rouge`() {
        val announcer = CrossingAnnouncer()
        repeat(3) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
        assertNull(announcer.onFrame(600L, CrossingState.Red))
        assertNull(announcer.onFrame(800L, CrossingState.Green))
        val cue = announcer.onFrame(1_000L, CrossingState.Red)
        assertEquals("Flashing green, do not cross.", cue?.phrase)
        assertTrue(cue?.urgent ?: false)
    }

    @Test
    fun `le doute sans rien d'annonce se dit des la deuxieme frame`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Unknown))
        val cue = announcer.onFrame(200L, CrossingState.Unknown)
        assertEquals("Light unclear, wait.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
        assertEquals(CrossingState.Unknown, cue?.state)
    }

    @Test
    fun `le doute garde le dernier connu sous TTL puis se dit et repete`() {
        val announcer = CrossingAnnouncer()
        repeat(3) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull(announcer.onFrame(600L, CrossingState.Unknown))
        assertNull(announcer.onFrame(800L, CrossingState.Unknown))
        assertEquals("persistance : le vert est garde", CrossingState.Green, announcer.lastCue?.state)
        assertNull(announcer.onFrame(1_000L, CrossingState.Unknown))
        val cue = announcer.onFrame(3_400L, CrossingState.Unknown)
        assertEquals("Light unclear, wait.", cue?.phrase)
        assertEquals(CrossingState.Unknown, announcer.lastCue?.state)
        assertNull(announcer.onFrame(4_000L, CrossingState.Unknown))
        assertEquals("Light unclear, wait.", announcer.onFrame(5_400L, CrossingState.Unknown)?.phrase)
    }

    @Test
    fun `le vert se repete toutes les deux secondes`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertEquals("Green, cross now.", announcer.lastCue?.phrase)
        assertNull(announcer.onFrame(1_500L, CrossingState.Green))
        val repeat = announcer.onFrame(2_600L, CrossingState.Green)
        assertEquals("Green, cross now.", repeat?.phrase)
        assertFalse(repeat?.urgent ?: true)
        assertEquals(CrossingState.Green, announcer.lastCue?.state)
        assertNull(announcer.onFrame(3_500L, CrossingState.Green))
        assertEquals("Green, cross now.", announcer.onFrame(4_600L, CrossingState.Green)?.phrase)
    }

    @Test
    fun `le rouge se repete toutes les deux secondes`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Red))
        assertEquals("Red, wait.", announcer.onFrame(200L, CrossingState.Red)?.phrase)
        assertNull(announcer.onFrame(1_000L, CrossingState.Red))
        assertNull("pas de répétition à 1 s", announcer.onFrame(1_300L, CrossingState.Red))
        val repeat = announcer.onFrame(2_200L, CrossingState.Red)
        assertEquals("Red, wait.", repeat?.phrase)
        assertFalse(repeat?.urgent ?: true)
        assertEquals(CrossingState.Red, announcer.lastCue?.state)
        assertNull(announcer.onFrame(3_000L, CrossingState.Red))
        assertEquals("Red, wait.", announcer.onFrame(4_200L, CrossingState.Red)?.phrase)
    }

    @Test
    fun `la repetition rouge suit le parametre`() {
        val announcer = CrossingAnnouncer(redRepeatMs = 60_000L)
        announcer.onFrame(0L, CrossingState.Red)
        announcer.onFrame(200L, CrossingState.Red)
        assertNull(announcer.onFrame(30_000L, CrossingState.Red))
        assertEquals("Red, wait.", announcer.onFrame(60_200L, CrossingState.Red)?.phrase)
    }

    @Test
    fun `la repetition verte suit le parametre`() {
        val announcer = CrossingAnnouncer(greenRepeatMs = 60_000L)
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        assertNull(announcer.onFrame(30_000L, CrossingState.Green))
        assertEquals("Green, cross now.", announcer.onFrame(60_600L, CrossingState.Green)?.phrase)
    }


    @Test
    fun `le retour au rouge apres un vert coupe tout`() {
        val announcer = CrossingAnnouncer()
        repeat(4) { announcer.onFrame(it * 200L, CrossingState.Green) }
        announcer.onFrame(800L, CrossingState.Red)
        val cue = announcer.onFrame(1_000L, CrossingState.Red)
        assertEquals("Red, wait.", cue?.phrase)
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
        assertEquals("Red, wait.", announcer.onFrame(600L, CrossingState.Red)?.phrase)
    }
}
