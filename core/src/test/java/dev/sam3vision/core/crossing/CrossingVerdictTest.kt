package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingVerdictTest {

    @Test
    fun `le rouge dit don't cross, voiture ou pas`() {
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Red, carMoving = false),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Red, carMoving = true),
        )
    }

    @Test
    fun `le vert tranche aux voitures`() {
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Green, carMoving = true),
        )
        assertEquals(
            CrossingVerdict.Cross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Green, carMoving = false),
        )
    }

    @Test
    fun `sans feu les voitures tranchent aussi`() {
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Absent, carMoving = true),
        )
        assertEquals(
            CrossingVerdict.Cross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Absent, carMoving = false),
        )
    }

    @Test
    fun `seule la voiture tranche`() {
        assertTrue(CrossingVerdictRule.isCarLabel("car"))
        assertTrue(CrossingVerdictRule.isCarLabel("Car"))
        assertTrue(CrossingVerdictRule.isCarLabel("cars"))
        assertTrue(CrossingVerdictRule.isCarLabel("voiture"))
        assertTrue(CrossingVerdictRule.isCarLabel("Voitures"))
        assertFalse(CrossingVerdictRule.isCarLabel("bus"))
        assertFalse(CrossingVerdictRule.isCarLabel("bicycle"))
        assertFalse(CrossingVerdictRule.isCarLabel("truck"))
        assertFalse(CrossingVerdictRule.isCarLabel("motorcycle"))
        assertFalse(CrossingVerdictRule.isCarLabel("scooter"))
        assertFalse(CrossingVerdictRule.isCarLabel("person"))
        assertFalse(CrossingVerdictRule.isCarLabel(""))
    }

    @Test
    fun `les phrases sont anglaises avec clip stable`() {
        assertEquals("Don't cross.", CrossingVerdictText.phrase(CrossingVerdict.DontCross))
        assertEquals("Cross.", CrossingVerdictText.phrase(CrossingVerdict.Cross))
        assertEquals("verdict_dont_cross", CrossingVerdictText.clipId(CrossingVerdict.DontCross))
        assertEquals("verdict_cross", CrossingVerdictText.clipId(CrossingVerdict.Cross))
    }

    @Test
    fun `le premier verdict parle aussitot`() {
        val announcer = CrossingVerdictAnnouncer()
        val cue = announcer.onFrame(0L, CrossingVerdict.Cross)
        assertEquals("Cross.", cue?.phrase)
        assertEquals("verdict_cross", cue?.clipId)
        assertFalse(cue?.changed ?: true)
    }

    @Test
    fun `un verdict maintenu se repete toutes les 3s`() {
        val announcer = CrossingVerdictAnnouncer()
        announcer.onFrame(0L, CrossingVerdict.DontCross)
        assertNull(announcer.onFrame(1_000L, CrossingVerdict.DontCross))
        assertNull(announcer.onFrame(2_999L, CrossingVerdict.DontCross))
        val repeat = announcer.onFrame(3_000L, CrossingVerdict.DontCross)
        assertEquals("Don't cross.", repeat?.phrase)
        assertFalse(repeat?.changed ?: true)
    }

    @Test
    fun `un changement parle aussitot`() {
        val announcer = CrossingVerdictAnnouncer()
        announcer.onFrame(0L, CrossingVerdict.DontCross)
        val cue = announcer.onFrame(200L, CrossingVerdict.Cross)
        assertEquals("Cross.", cue?.phrase)
        assertTrue(cue?.changed ?: false)
    }

    @Test
    fun `reset refait parler le premier verdict`() {
        val announcer = CrossingVerdictAnnouncer()
        announcer.onFrame(0L, CrossingVerdict.Cross)
        announcer.reset()
        assertNull(announcer.lastCue)
        assertEquals(CrossingVerdict.Cross, announcer.onFrame(100L, CrossingVerdict.Cross)?.verdict)
    }

    @Test
    fun `l'ordre est verdict puis humains puis bordures`() {
        val verdict = CrossingPost(CrossingLevels.VERDICT, "Don't cross.", "verdict_dont_cross")
        val human = CrossingPost(CrossingLevels.HUMAN, "Person at 12.", null)
        val curb = CrossingPost(CrossingLevels.CURB, "Curb ahead.", null)
        assertEquals(listOf(verdict, human, curb), CrossingPriority.order(verdict, human, curb))
        assertEquals(listOf(verdict), CrossingPriority.order(verdict, null, null))
        assertEquals(emptyList<CrossingPost>(), CrossingPriority.order(null, null, null))
    }

    @Test
    fun `les niveaux sont 100 101 102`() {
        assertEquals(100, CrossingLevels.VERDICT)
        assertEquals(101, CrossingLevels.HUMAN)
        assertEquals(102, CrossingLevels.CURB)
    }

    @Test
    fun `en traversee chat et GPS muets, dictee active`() {
        assertTrue(CrossingExclusivity.chatMuted(traversing = true))
        assertTrue(CrossingExclusivity.gpsMuted(traversing = true))
        assertFalse(CrossingExclusivity.chatMuted(traversing = false))
        assertFalse(CrossingExclusivity.gpsMuted(traversing = false))
        assertTrue(CrossingExclusivity.dictationActive())
    }

    @Test
    fun `les prompts mettent le feu et la voiture seule devant`() {
        val prompts = CrossingVerdictPrompts.promptsFor(listOf("sidewalk"))
        assertEquals("pedestrian traffic light", prompts.first())
        assertTrue(prompts.contains("car"))
        assertFalse(prompts.contains("bus"))
        assertFalse(prompts.contains("bicycle"))
        assertFalse(prompts.contains("truck"))
        assertFalse(prompts.contains("motorcycle"))
        assertTrue(prompts.contains("person"))
        assertTrue(prompts.contains("crosswalk"))
        assertTrue(prompts.contains("curb"))
        assertTrue(prompts.contains("sidewalk"))
    }
}
