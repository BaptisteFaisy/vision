package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voix de la sonde : le guidage (cap route, sinon cap manuel) prime sur
 * l'annonce, et le chemin dessiné est celui de la décision parlée.
 */
class ProbeVoiceTest {

    private val libre = emptyList<PlacedLabel>()
    private fun personne(meters: Float = 2f) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    private fun guided(): ProbeVoice = ProbeVoice().also { it.guide = true }

    @Test
    fun `guide coupe et annonce coupee restent muets`() {
        val voice = ProbeVoice()

        assertNull(voice.phrase(0L, libre, SteeringCap.Straight))
        assertNull(voice.phrase(500L, libre, SteeringCap.Straight))
        assertNull(voice.cue())
        assertFalse(voice.detour)
    }

    @Test
    fun `guidage dit tout droit sur voie libre`() {
        val voice = guided()

        assertEquals("Straight.", voice.phrase(0L, libre, SteeringCap.Straight))
        assertNull(voice.phrase(500L, libre, SteeringCap.Straight))
    }

    @Test
    fun `cap route prime sur le cap manuel`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right

        assertEquals("Right.", voice.phrase(0L, libre, SteeringCap.Left))
        assertNull(voice.phrase(1_000L, libre, SteeringCap.Left))
    }

    @Test
    fun `chemin nul avant la premiere frame guidee`() {
        assertNull(guided().cue())
    }

    @Test
    fun `chemin porte le passage et le detour devant un obstacle`() {
        val voice = guided()
        voice.phrase(0L, listOf(personne()), SteeringCap.Straight)
        voice.phrase(500L, listOf(personne()), SteeringCap.Straight)

        val cue = voice.cue()
        assertTrue("chemin attendu", cue != null)
        assertEquals(SteeringCap.Straight, cue?.cap)
        assertEquals(true, cue?.blocked)
        assertEquals(SteeringCap.Left, cue?.arrowDir())
        assertTrue(voice.detour)
    }

    @Test
    fun `voie libre ni bloquee ni en detour`() {
        val voice = guided()
        voice.phrase(0L, libre, SteeringCap.Left)
        voice.phrase(500L, libre, SteeringCap.Left)

        val cue = voice.cue()
        assertEquals(SteeringCap.Left, cue?.cap)
        assertEquals(false, cue?.blocked)
        assertEquals(SteeringCap.Left, cue?.arrowDir())
        assertFalse(voice.detour)
    }

    @Test
    fun `mur au contact - stop`() {
        val voice = guided()
        val phrase = voice.phrase(0L, listOf(PlacedLabel("wall", 0.5f, 0.5f, 0.5f)), SteeringCap.Straight)

        assertTrue(GuidanceWord.isStopPhrase(phrase!!))
    }

    @Test
    fun `mot doux - phrase parlee`() {
        val voice = guided()


        assertEquals("Straight.", voice.phrase(0L, libre, SteeringCap.Straight))
        assertFalse(voice.stopRecent(1_000L))
    }

    @Test
    fun `passage pieton filtre avant guidage - jamais stop`() {
        val voice = guided()

        assertEquals(
            "Straight.",
            voice.phrase(0L, listOf(PlacedLabel("crosswalk", 0.5f, 0.5f, 0.4f)), SteeringCap.Straight),
        )
    }

    @Test
    fun `changer de cap route repart de zero`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right
        voice.phrase(0L, libre, SteeringCap.Left)
        voice.phrase(500L, libre, SteeringCap.Left)
        assertTrue(voice.cue() != null)

        voice.routeCap = SteeringCap.Left

        assertNull(voice.cue())
        assertFalse(voice.detour)
    }

    @Test
    fun `reset oublie chemin et detour`() {
        val voice = guided()
        voice.phrase(0L, listOf(personne()), SteeringCap.Straight)
        voice.phrase(500L, listOf(personne()), SteeringCap.Straight)
        assertTrue(voice.detour)

        voice.reset()

        assertNull(voice.cue())
        assertFalse(voice.detour)
    }

    @Test
    fun `chemin porte le decalage urgent quand une personne s approche`() {
        val voice = guided()
        val droite = { m: Float -> PlacedLabel("personne", 0.7f, 0.5f, m) }
        voice.phrase(0L, listOf(droite(3.0f)), SteeringCap.Straight)
        voice.phrase(250L, listOf(droite(2.6f)), SteeringCap.Straight)
        voice.phrase(500L, listOf(droite(2.2f)), SteeringCap.Straight)

        val cue = voice.cue()

        assertEquals(SteeringCap.Left, cue!!.passage)
        assertTrue(cue.urgent)
        assertFalse(cue.blocked)
    }
}
