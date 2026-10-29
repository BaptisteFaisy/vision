package dev.sam3vision.app.isee

import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maintien trottoir dans la voix de la sonde : le cap route est suspendu quand
 * l'utilisateur n'est plus sur le trottoir (voix + flèche vers le retour), repris
 * une fois dessus ; le bouché prime toujours, l'absence parle prudemment.
 */
class ProbeVoiceSidewalkTest {

    private fun trottoir(x: Float) = PlacedLabel("sidewalk", x, 0.7f, null)

    private fun personne(meters: Float = 2f) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    private fun guided(): ProbeVoice = ProbeVoice().also { it.guide = true }

    @Test
    fun `trottoir a gauche suspend le cap route, voix et fleche`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right
        val labels = listOf(trottoir(0.1f))

        assertNull(voice.phrase(0L, labels, SteeringCap.Right))
        assertEquals("Remonte sur le trottoir, à gauche.", voice.phrase(500L, labels, SteeringCap.Right))
        assertEquals(SteeringCap.Left, voice.cue()?.arrowDir())
        assertTrue(voice.detour)
    }

    @Test
    fun `trottoir a droite ordonne le retour a droite`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Left
        val labels = listOf(trottoir(0.9f))

        assertNull(voice.phrase(0L, labels, SteeringCap.Left))
        assertEquals("Remonte sur le trottoir, à droite.", voice.phrase(500L, labels, SteeringCap.Left))
        assertEquals(SteeringCap.Right, voice.cue()?.arrowDir())
        assertTrue(voice.detour)
    }

    @Test
    fun `trottoir devant reprend le cap route`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right
        val labels = listOf(trottoir(0.5f))

        assertNull(voice.phrase(0L, labels, SteeringCap.Right))
        assertEquals("Va à droite.", voice.phrase(500L, labels, SteeringCap.Right))
        assertEquals(SteeringCap.Right, voice.cue()?.arrowDir())
        assertFalse(voice.detour)
    }

    @Test
    fun `scene vide garde le cap route, pas de correction sans preuve`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right

        assertNull(voice.phrase(0L, emptyList(), SteeringCap.Right))
        assertEquals("Va à droite.", voice.phrase(500L, emptyList(), SteeringCap.Right))
        assertFalse(voice.detour)
    }

    @Test
    fun `scene percue sans trottoir = consigne prudente, fleche inchangee`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right
        val labels = listOf(personne(10f))

        assertNull(voice.phrase(0L, labels, SteeringCap.Right))
        assertEquals("Trottoir introuvable, avancez lentement.", voice.phrase(500L, labels, SteeringCap.Right))
        assertEquals(SteeringCap.Right, voice.cue()?.arrowDir())
        assertFalse(voice.detour)
    }

    @Test
    fun `obstacle prioritaire sur le retour trottoir`() {
        val voice = guided()
        voice.routeCap = SteeringCap.Right
        val labels = listOf(personne(2f), trottoir(0.1f))
        voice.phrase(0L, labels, SteeringCap.Right)

        val phrase = voice.phrase(500L, labels, SteeringCap.Right).orEmpty()
        assertTrue("consigne obstacle attendue, reçu : $phrase", phrase.startsWith("Personne"))
        assertEquals(SteeringCap.Left, voice.cue()?.arrowDir())
        assertTrue(voice.detour)
    }
}
