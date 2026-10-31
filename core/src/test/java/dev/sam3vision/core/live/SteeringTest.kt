package dev.sam3vision.core.live

import dev.sam3vision.core.nav.NavigationIntentParser
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Ordre vocal de cap : formes reconnues, questions épargnées, confirmations, chemin. */
class SteeringTest {

    @Test
    fun `va a gauche donne le cap gauche`() {
        assertEquals(SteeringCap.Left, SteeringCommand.parse("va à gauche"))
    }

    @Test
    fun `formes droites et tout droit`() {
        assertEquals(SteeringCap.Right, SteeringCommand.parse("tourne à droite !"))
        assertEquals(SteeringCap.Straight, SteeringCommand.parse("continue tout droit"))
        assertEquals(SteeringCap.Straight, SteeringCommand.parse("je veux aller tout droit"))
        assertEquals(SteeringCap.Straight, SteeringCommand.parse("droit devant, on avance"))
    }

    @Test
    fun `formes anglaises`() {
        assertEquals(SteeringCap.Left, SteeringCommand.parse("go left"))
        assertEquals(SteeringCap.Right, SteeringCommand.parse("turn right"))
        assertEquals(SteeringCap.Straight, SteeringCommand.parse("go straight ahead"))
    }

    @Test
    fun `sans verbe de mouvement pas d ordre`() {
        assertNull(SteeringCommand.parse("qu'est-ce qu'il y a à gauche ?"))
        assertNull(SteeringCommand.parse("la porte est à droite"))
        assertNull(SteeringCommand.parse(""))
        assertNull(SteeringCommand.parse("   "))
    }

    @Test
    fun `sans direction pas d ordre`() {
        assertNull(SteeringCommand.parse("il va à Paris"))
        assertNull(SteeringCommand.parse("où est la sortie ?"))
    }

    @Test
    fun `deux directions c est ambigu`() {
        assertNull(SteeringCommand.parse("va à gauche et à droite"))
    }

    @Test
    fun `ordre cap prioritaire sur l intention Maps`() {
        // « va à gauche » matche aussi « va à X » : le hook cap DOIT passer
        // avant le routeur navigation, sinon Maps s'ouvre. Ce test verrouille
        // la collision, pas l'ordre (une ligne du ViewModel, voir handleDictated).
        assertNotNull(NavigationIntentParser.parse("va à gauche"))
        assertEquals(SteeringCap.Left, SteeringCommand.parse("va à gauche"))
    }

    @Test
    fun `confirmation guidee en francais`() {
        assertEquals(
            "Je te guide vers la gauche.",
            SteeringText.confirm(SteeringCap.Left, guided = true, SpeechLanguage.Fr),
        )
        assertEquals(
            "Je te guide tout droit.",
            SteeringText.confirm(SteeringCap.Straight, guided = true, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `confirmation dit quand le guidage est coupe`() {
        assertEquals(
            "Heading right. Turn guidance on in Settings.",
            SteeringText.confirm(SteeringCap.Right, guided = false),
        )
    }

    @Test
    fun `confirmation anglaise`() {
        assertEquals(
            "Guiding you straight ahead.",
            SteeringText.confirm(SteeringCap.Straight, guided = true, SpeechLanguage.En),
        )
        assertEquals(
            "Heading left. Turn guidance on in Settings.",
            SteeringText.confirm(SteeringCap.Left, guided = false, SpeechLanguage.En),
        )
    }

    @Test
    fun `chemin libre pointe le cap`() {
        val cue = SteeringCue.from(Guidance.decide(emptyList(), SteeringCap.Left))

        assertEquals(SteeringCap.Left, cue.cap)
        assertEquals(false, cue.blocked)
        assertNull(cue.passage)
        assertEquals(SteeringCap.Left, cue.arrowDir())
    }

    @Test
    fun `chemin bouche pointe le passage`() {
        val labels = listOf(PlacedLabel("personne", 0.2f, 0.5f, 2f))
        val cue = SteeringCue.from(Guidance.decide(labels, SteeringCap.Left))

        assertEquals(true, cue.blocked)
        assertEquals(SteeringCap.Right, cue.passage)
        assertEquals(SteeringCap.Right, cue.arrowDir())
    }
}
