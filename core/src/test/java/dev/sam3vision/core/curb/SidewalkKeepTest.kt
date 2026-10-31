package dev.sam3vision.core.curb

import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maintien trottoir : position vue caméra, consignes de retour, prudence au doute. */
class SidewalkKeepTest {

    private fun trottoir(x: Float, meters: Float? = null) = PlacedLabel("sidewalk", x, 0.7f, meters)

    private fun personne(x: Float = 0.5f) = PlacedLabel("personne", x, 0.5f, 10f)

    @Test
    fun `scene vide = devant, pas de correction`() {
        assertEquals(SidewalkPosition.Ahead, SidewalkKeep.positionOf(emptyList()))
        assertEquals(SidewalkPosition.Ahead, SidewalkKeep.positionOf(listOf(PlacedLabel("  ", 0.5f))))
    }

    @Test
    fun `trottoir dans le couloir = devant`() {
        assertEquals(SidewalkPosition.Ahead, SidewalkKeep.positionOf(listOf(trottoir(0.5f))))
        assertEquals(SidewalkPosition.Ahead, SidewalkKeep.positionOf(listOf(trottoir(0.34f))))
        assertEquals(SidewalkPosition.Ahead, SidewalkKeep.positionOf(listOf(trottoir(0.65f))))
    }

    @Test
    fun `trottoir sur un cote = ce cote`() {
        assertEquals(SidewalkPosition.Left, SidewalkKeep.positionOf(listOf(trottoir(0.1f))))
        assertEquals(SidewalkPosition.Right, SidewalkKeep.positionOf(listOf(trottoir(0.9f))))
    }

    @Test
    fun `scene percue sans trottoir = absent`() {
        assertEquals(SidewalkPosition.Absent, SidewalkKeep.positionOf(listOf(personne())))
    }

    @Test
    fun `libelle francais trottoir reconnu`() {
        val labels = listOf(PlacedLabel("Trottoir", 0.1f, 0.7f, null))

        assertEquals(SidewalkPosition.Left, SidewalkKeep.positionOf(labels))
    }

    @Test
    fun `deux cotes = le plus proche`() {
        val gauchePres = listOf(trottoir(0.1f, 2f), trottoir(0.9f, 5f))
        val droitePres = listOf(trottoir(0.1f, 5f), trottoir(0.9f, 2f))

        assertEquals(SidewalkPosition.Left, SidewalkKeep.positionOf(gauchePres))
        assertEquals(SidewalkPosition.Right, SidewalkKeep.positionOf(droitePres))
    }

    @Test
    fun `deux cotes sans distance = absent, le doute ne choisit pas`() {
        val labels = listOf(trottoir(0.1f), trottoir(0.9f))

        assertEquals(SidewalkPosition.Absent, SidewalkKeep.positionOf(labels))
    }

    @Test
    fun `deux cotes, un seul mesure = le mesure`() {
        val labels = listOf(trottoir(0.1f), trottoir(0.9f, 3f))

        assertEquals(SidewalkPosition.Right, SidewalkKeep.positionOf(labels))
    }

    @Test
    fun `sol marchable reconnu, bordure et marche non`() {
        assertTrue(CurbConcepts.isGround("sidewalk"))
        assertTrue(CurbConcepts.isGround("pavement"))
        assertTrue(CurbConcepts.isGround("footpath"))
        assertTrue(CurbConcepts.isGround("Trottoir"))
        assertTrue(CurbConcepts.isGround("sidewalks"))
        assertTrue(CurbConcepts.isGround("curb ramp"))
        assertTrue(CurbConcepts.isGround("bateau"))

        assertFalse(CurbConcepts.isGround("curb"))
        assertFalse(CurbConcepts.isGround("step"))
        assertFalse(CurbConcepts.isGround("bordure"))
        assertFalse(CurbConcepts.isGround("rebord"))
        assertFalse(CurbConcepts.isGround("car"))
        assertFalse(CurbConcepts.isGround("  "))
    }

    @Test
    fun `marches et bordures seules = absent, pas un trottoir`() {
        val marches = listOf(PlacedLabel("steps", 0.1f, 0.7f, 2f), personne(0.9f))
        val bordure = listOf(PlacedLabel("curb", 0.9f, 0.7f, 2f))

        assertEquals(SidewalkPosition.Absent, SidewalkKeep.positionOf(marches))
        assertEquals(SidewalkPosition.Absent, SidewalkKeep.positionOf(bordure))
    }

    @Test
    fun `cap de retour vers le cote, nul devant et absent`() {
        assertEquals(SteeringCap.Left, SidewalkKeep.returnCap(SidewalkPosition.Left))
        assertEquals(SteeringCap.Right, SidewalkKeep.returnCap(SidewalkPosition.Right))
        assertNull(SidewalkKeep.returnCap(SidewalkPosition.Ahead))
        assertNull(SidewalkKeep.returnCap(SidewalkPosition.Absent))
    }

    @Test
    fun `deux cotes sans distance = consigne prudente, pas de deviation`() {
        val labels = listOf(trottoir(0.1f), trottoir(0.9f))

        assertEquals("Attention, trottoir des deux côtés.", SidewalkKeep.cautionPhrase(labels, SpeechLanguage.Fr))
        assertEquals("Attention, sidewalk on both sides.", SidewalkKeep.cautionPhrase(labels, SpeechLanguage.En))
        assertNull(SidewalkKeep.returnCap(SidewalkKeep.positionOf(labels)))
    }

    @Test
    fun `consigne prudente nulle sinon`() {
        assertNull(SidewalkKeep.cautionPhrase(listOf(trottoir(0.1f, 2f), trottoir(0.9f, 5f))))
        assertNull(SidewalkKeep.cautionPhrase(listOf(trottoir(0.1f))))
        assertNull(SidewalkKeep.cautionPhrase(listOf(trottoir(0.5f))))
        assertNull(SidewalkKeep.cautionPhrase(listOf(personne())))
        assertNull(SidewalkKeep.cautionPhrase(emptyList()))
    }

    @Test
    fun `en face = info douce, pas de correction`() {
        assertNull(SidewalkKeep.returnCap(SidewalkPosition.Across))
    }

}
