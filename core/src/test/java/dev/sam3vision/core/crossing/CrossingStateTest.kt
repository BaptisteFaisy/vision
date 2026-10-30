package dev.sam3vision.core.crossing

import dev.sam3vision.core.live.LivePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingStateTest {

    @Test
    fun `quatre prompts exactement, plafond client respecte`() {
        assertEquals(4, CrossingPrompts.PROMPTS.size)
        assertTrue(CrossingPrompts.PROMPTS.size <= LivePrompts.MAX)
        assertEquals(CrossingPrompts.PROMPTS, LivePrompts.normalize(CrossingPrompts.PROMPTS))
    }

    @Test
    fun `les prompts suivent le feu les voitures les personnes et le passage`() {
        assertEquals(
            listOf("traffic light", "car", "person", "crosswalk"),
            CrossingPrompts.PROMPTS,
        )
    }

    @Test
    fun `les etiquettes anglaises de feu sont reconnues`() {
        assertTrue(CrossingPrompts.isLightLabel("traffic light"))
        assertTrue(CrossingPrompts.isLightLabel("Traffic Light"))
        assertTrue(CrossingPrompts.isLightLabel("pedestrian light"))
        assertTrue(CrossingPrompts.isLightLabel("stoplight"))
    }

    @Test
    fun `les etiquettes francaises de feu sont reconnues`() {
        assertTrue(CrossingPrompts.isLightLabel("feu tricolore"))
        assertTrue(CrossingPrompts.isLightLabel("Feu piéton"))
        assertTrue(CrossingPrompts.isLightLabel("  feu  "))
    }

    @Test
    fun `le reste n est pas un feu`() {
        assertFalse(CrossingPrompts.isLightLabel("car"))
        assertFalse(CrossingPrompts.isLightLabel("person"))
        assertFalse(CrossingPrompts.isLightLabel(""))
        assertFalse(CrossingPrompts.isLightLabel("door"))
    }

    @Test
    fun `la boite du feu au meilleur score est normalisee`() {
        val objects = listOf(
            light("car", 0.99f, listOf(0f, 0f, 10f, 10f)),
            light("traffic light", 0.60f, listOf(0f, 0f, 100f, 200f)),
            light("traffic light", 0.91f, listOf(50f, 100f, 150f, 300f)),
        )
        assertEquals(listOf(0.25f, 0.25f, 0.75f, 0.75f), CrossingPrompts.selectLightBox(objects, 200, 400))
    }

    @Test
    fun `sans feu detecte pas de boite`() {
        val objects = listOf(light("car", 0.99f, listOf(0f, 0f, 10f, 10f)))
        assertEquals(null, CrossingPrompts.selectLightBox(objects, 200, 400))
        assertEquals(null, CrossingPrompts.selectLightBox(emptyList(), 200, 400))
    }

    @Test
    fun `une boite invalide ne passe pas`() {
        val flat = listOf(light("traffic light", 0.9f, listOf(10f, 10f, 10f, 50f)))
        assertEquals(null, CrossingPrompts.selectLightBox(flat, 200, 400))
        val short = listOf(light("traffic light", 0.9f, listOf(10f, 10f)))
        assertEquals(null, CrossingPrompts.selectLightBox(short, 200, 400))
        val noSize = listOf(light("traffic light", 0.9f, listOf(0f, 0f, 10f, 10f)))
        assertEquals(null, CrossingPrompts.selectLightBox(noSize, 0, 0))
    }

    private fun light(label: String, score: Float, box: List<Float>) =
        dev.sam3vision.core.model.SegObject(label, score, box, "")
}
