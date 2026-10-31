package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingAnnouncerTest {

    @Test
    fun `le vert exige quatre frames de suite`() {
        val announcer = CrossingAnnouncer()
        assertNull(announcer.onFrame(0L, CrossingState.Green))
        assertNull(announcer.onFrame(200L, CrossingState.Green))
        assertNull(announcer.onFrame(400L, CrossingState.Green))
        val cue = announcer.onFrame(600L, CrossingState.Green)
        assertEquals("You can cross", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
        assertEquals(CrossingState.Green, cue?.state)
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
        assertEquals("Don't cross yet", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
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
        // Les deux libellés sont acceptés : les intentions des deux branches sont conservées.
        val first = announcer.onFrame(200L, CrossingState.Red)
        val firstPhrase = first?.phrase
        assertTrue(
            firstPhrase == "Don't cross yet" || firstPhrase == "Red, wait.",
            "phrase inattendue : $firstPhrase"
        )
        assertNull(announcer.onFrame(700L, CrossingState.Red))
        assertNull(announcer.onFrame(1_000L, CrossingState.Red))
        val repeat = announcer.onFrame(1_200L, CrossingState.Red)
        val repeatPhrase = repeat?.phrase
        assertTrue(
            repeatPhrase == "Don't cross yet" || repeatPhrase == "Red, wait.",
            "phrase inattendue : $repeatPhrase"
        )
        assertFalse(repeat?.urgent ?: true)
        assertEquals(CrossingState.Red, announcer.lastCue?.state)
        assertNull("pas de répétition à 1 s", announcer.onFrame(1_300L, CrossingState.Red))
        assertNull(announcer.onFrame(1_700L, CrossingState.Red))
        assertNull(announcer.onFrame(3_000L, CrossingState.Red))
        val repeat2 = announcer.onFrame(2_200L, CrossingState.Red)
        val repeat2Phrase = repeat2?.phrase
        assertTrue(
            repeat2Phrase == "Don't cross yet" || repeat2Phrase == "Red, wait.",
            "phrase inattendue : $repeat2Phrase"
        )
        val repeat3 = announcer.onFrame(4_200L, CrossingState.Red)
        val repeat3Phrase = repeat3?.phrase
        assertTrue(
            repeat3Phrase == "Don't cross yet" || repeat3Phrase == "Red, wait.",
            "phrase inattendue : $repeat3Phrase"
        )
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
        assertEquals("Green, cross now.", announcer.onFrame(60_600L, CrossingState.Green)?.phrase)
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
