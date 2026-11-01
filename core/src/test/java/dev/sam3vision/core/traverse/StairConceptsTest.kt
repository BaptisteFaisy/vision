package dev.sam3vision.core.traverse

import dev.sam3vision.core.depth.DepthReadings
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le concept escalier est reconnu (FR/EN) et câblé dans les défauts du
 * direct ; sa distance vient de la profondeur, pas du sténopé (aucune
 * hauteur d'escalier n'est défendable).
 */
class StairConceptsTest {

    @Test
    fun `isStairs reconnait anglais francais et pluriels`() {
        assertTrue(StairConcepts.isStairs("stairs"))
        assertTrue(StairConcepts.isStairs("Stair"))
        assertTrue(StairConcepts.isStairs("STEPS"))
        assertTrue(StairConcepts.isStairs("staircase"))
        assertTrue(StairConcepts.isStairs("stairway"))
        assertTrue(StairConcepts.isStairs("escalier"))
        assertTrue(StairConcepts.isStairs("Escalier"))
        assertTrue(StairConcepts.isStairs("escaliers"))
        assertTrue(StairConcepts.isStairs("  ESCALIERS  "))
    }

    @Test
    fun `isStairs refuse le reste dont marche seul ambigue`() {
        assertFalse(StairConcepts.isStairs("person"))
        assertFalse(StairConcepts.isStairs("door"))
        assertFalse(StairConcepts.isStairs("car"))
        assertFalse(StairConcepts.isStairs("marche"))
        assertFalse(StairConcepts.isStairs(""))
        assertFalse(StairConcepts.isStairs("   "))
    }

    @Test
    fun `promptsFor met stairs en tete borne au plafond`() {
        assertEquals(listOf("stairs"), StairConcepts.promptsFor())
        val prompts = StairConcepts.promptsFor(listOf("person", "door"))
        assertEquals(listOf("stairs", "person", "door"), prompts)
        val full = StairConcepts.promptsFor((1..30).map { "objet$it" })
        assertEquals(LivePrompts.MAX, full.size)
        assertEquals("stairs", full.first())
    }

    @Test
    fun `defauts dangers exterieur dont stairs, plafond dix huit`() {
        assertEquals(
            listOf(
                "person", "car", "bus", "bicycle", "motorcycle",
                "pedestrian traffic light", "pedestrian signal head",
                "curb", "sidewalk", "crosswalk", "pole", "stairs", "keys", "gate",
                "souvenir shop", "beanie", "socks", "wall", "stroller", "child",
                "dog",
            ),
            LivePrompts.default,
        )
        assertEquals(22, LivePrompts.MAX)
        assertEquals(
            listOf("car", "bus", "bicycle", "truck", "motorcycle"),
            VehicleConcepts.PROMPTS,
        )
        assertTrue("stairs" in LivePrompts.default)
    }

    @Test
    fun `escalier sans hauteur connue ni stenope`() {
        assertNull(KnownSizes.heightMeters("stairs"))
        assertNull(KnownSizes.heightMeters("escalier"))
        assertNull(KnownSizes.heightMeters("staircase"))
    }

    @Test
    fun `escalier mesure par la profondeur calibree sur une ancre`() {
        val personBox = listOf(400f, 100f, 500f, 400f)
        val stairsBox = listOf(100f, 100f, 300f, 400f)
        val detections = listOf(
            Detection("person", 0.9f, personBox, width = 640, height = 480),
            Detection("stairs", 0.9f, stairsBox, width = 640, height = 480),
        )
        val pinhole = listOf(DistanceReading(1, "person", 5f, 5f, personBox))
        val closeByBox = mapOf(personBox to 2.0f, stairsBox to 1.0f)
        val refined = DepthReadings.refineWithCloseness(detections, pinhole, closeByBox)
        assertEquals(2, refined.size)
        val stairs = refined.single { it.label == "stairs" }
        assertEquals(10f, stairs.meters, 0.001f)
    }
}
