package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les quatre mots : textes FR/EN (miroir du catalogue), clips, clés, caps. */
class GuidanceWordTest {

    @Test
    fun `textes francais`() {
        assertEquals("Tout droit.", GuidanceWord.Straight.text(SpeechLanguage.Fr))
        assertEquals("Gauche.", GuidanceWord.Left.text(SpeechLanguage.Fr))
        assertEquals("Droite.", GuidanceWord.Right.text(SpeechLanguage.Fr))
        assertEquals("Stop.", GuidanceWord.Stop.text(SpeechLanguage.Fr))
    }

    @Test
    fun `textes anglais`() {
        assertEquals("Straight.", GuidanceWord.Straight.text(SpeechLanguage.En))
        assertEquals("Left.", GuidanceWord.Left.text(SpeechLanguage.En))
        assertEquals("Right.", GuidanceWord.Right.text(SpeechLanguage.En))
        assertEquals("Stop.", GuidanceWord.Stop.text(SpeechLanguage.En))
    }

    @Test
    fun `clips et cles`() {
        assertEquals("guide_straight", GuidanceWord.Straight.clipId)
        assertEquals("guide_left", GuidanceWord.Left.clipId)
        assertEquals("guide_right", GuidanceWord.Right.clipId)
        assertEquals("guide_stop", GuidanceWord.Stop.clipId)
        assertEquals("w|straight", GuidanceWord.Straight.key)
        assertEquals("w|left", GuidanceWord.Left.key)
        assertEquals("w|right", GuidanceWord.Right.key)
        assertEquals("w|stop", GuidanceWord.Stop.key)
    }

    @Test
    fun `caps et cotes donnent le mot`() {
        assertEquals(GuidanceWord.Left, GuidanceWord.fromCap(SteeringCap.Left))
        assertEquals(GuidanceWord.Straight, GuidanceWord.fromCap(SteeringCap.Straight))
        assertEquals(GuidanceWord.Right, GuidanceWord.fromCap(SteeringCap.Right))
        assertEquals(GuidanceWord.Left, GuidanceWord.fromSide(GuidanceSide.Left))
        assertEquals(GuidanceWord.Right, GuidanceWord.fromSide(GuidanceSide.Right))
    }

    @Test
    fun `phrase parlee donne le clip, autre phrase donne rien`() {
        assertEquals("guide_left", GuidanceWord.clipIdFor("Left."))
        assertEquals("guide_left", GuidanceWord.clipIdFor("Gauche."))
        assertEquals("guide_straight", GuidanceWord.clipIdFor("Straight."))
        assertEquals("guide_straight", GuidanceWord.clipIdFor("Tout droit."))
        assertEquals("guide_right", GuidanceWord.clipIdFor("Right."))
        assertEquals("guide_right", GuidanceWord.clipIdFor("Droite."))
        assertEquals("guide_stop", GuidanceWord.clipIdFor("Stop."))
        assertNull(GuidanceWord.clipIdFor("Personne à 9 heures."))
        assertNull(GuidanceWord.clipIdFor(""))
    }

    @Test
    fun `seul stop coupe`() {
        assertTrue(GuidanceWord.isStopPhrase("Stop."))
        assertTrue(GuidanceWord.isStopPhrase(GuidanceWord.Stop.text(SpeechLanguage.Fr)))
        assertTrue(GuidanceWord.isStopPhrase(GuidanceWord.Stop.text(SpeechLanguage.En)))
        assertTrue(GuidanceWord.isStopPhrase("obstacle, arrêtez-vous."))
        assertTrue(GuidanceWord.isStopPhrase("obstacle, stop."))
        assertFalse(GuidanceWord.isStopPhrase("Gauche."))
        assertFalse(GuidanceWord.isStopPhrase("Straight."))
        assertFalse(GuidanceWord.isStopPhrase("gauche."))
        assertFalse(GuidanceWord.isStopPhrase("Personne à 9 heures."))
    }

    @Test
    fun `virage gauche droite, pas tout droit ni stop`() {
        assertTrue(GuidanceWord.isTurnWord("Gauche."))
        assertTrue(GuidanceWord.isTurnWord("Droite."))
        assertTrue(GuidanceWord.isTurnWord("Left."))
        assertTrue(GuidanceWord.isTurnWord("Right."))
        assertFalse(GuidanceWord.isTurnWord("Tout droit."))
        assertFalse(GuidanceWord.isTurnWord("Straight."))
        assertFalse(GuidanceWord.isTurnWord("Stop."))
        assertFalse(GuidanceWord.isTurnWord("gauche."))
        assertFalse(GuidanceWord.isTurnWord("Personne à 9 heures."))
        assertFalse(GuidanceWord.isTurnWord(""))
    }
}
