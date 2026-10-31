package dev.sam3vision.core.crossing

import dev.sam3vision.core.live.LivePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingStateTest {

    @Test
    fun `six prompts exactement, plafond client respecte`() {
        assertEquals(6, CrossingPrompts.PROMPTS.size)
        assertTrue(CrossingPrompts.PROMPTS.size <= LivePrompts.MAX)
        assertEquals(CrossingPrompts.PROMPTS, LivePrompts.normalize(CrossingPrompts.PROMPTS))
    }

    @Test
    fun `les prompts suivent le feu pieton en trois variantes plus voitures personnes et passage`() {
        assertEquals(
            listOf(
                "pedestrian traffic light", "pedestrian light", "pedestrian signal",
                "car", "person", "crosswalk",
            ),
            CrossingPrompts.PROMPTS,
        )
    }

    @Test
    fun `les trois wordings feu sont envoyes et reconnus, alignement`() {
        assertEquals(
            listOf("pedestrian traffic light", "pedestrian light", "pedestrian signal"),
            CrossingPrompts.LIGHTS,
        )
        CrossingPrompts.LIGHTS.forEach { wording ->
            assertTrue(wording, wording in CrossingPrompts.PROMPTS)
            assertTrue(wording, CrossingPrompts.isLightLabel(wording))
        }
    }

    @Test
    fun `les etiquettes pietonnes anglaises sont reconnues`() {
        assertTrue(CrossingPrompts.isLightLabel("pedestrian traffic light"))
        assertTrue(CrossingPrompts.isLightLabel("Pedestrian Traffic Light"))
        assertTrue(CrossingPrompts.isLightLabel("pedestrian light"))
        assertTrue(CrossingPrompts.isLightLabel("pedestrian signal"))
    }

    @Test
    fun `les etiquettes pietonnes francaises sont reconnues`() {
        assertTrue(CrossingPrompts.isLightLabel("Feu piéton"))
        assertTrue(CrossingPrompts.isLightLabel("feux piétons"))
        assertTrue(CrossingPrompts.isLightLabel("  feu pieton  "))
        assertTrue(CrossingPrompts.isLightLabel("signal piéton"))
    }

    @Test
    fun `les feux voiture sont ignores, ordre user`() {
        assertFalse(CrossingPrompts.isLightLabel("traffic light"))
        assertFalse(CrossingPrompts.isLightLabel("Traffic Light"))
        assertFalse(CrossingPrompts.isLightLabel("stoplight"))
        assertFalse(CrossingPrompts.isLightLabel("traffic signal"))
        assertFalse(CrossingPrompts.isLightLabel("feu tricolore"))
        assertFalse(CrossingPrompts.isLightLabel("  feu  "))
    }

    @Test
    fun `un passant pieton n est pas un feu`() {
        assertFalse(CrossingPrompts.isLightLabel("piéton"))
        assertFalse(CrossingPrompts.isLightLabel("pedestrian"))
    }

    @Test
    fun `l abrege oral feu pi reste un feu`() {
        assertTrue(CrossingPrompts.isLightLabel("feu pi"))
    }

    @Test
    fun `un compose pieton plus lampe reste un feu`() {
        assertTrue(CrossingPrompts.isLightLabel("feu piéton rouge"))
        assertTrue(CrossingPrompts.isLightLabel("green pedestrian traffic light"))
        assertTrue(CrossingPrompts.isLightLabel("red pedestrian light"))
    }

    @Test
    fun `un passage pieton n est pas un feu`() {
        assertFalse(CrossingPrompts.isLightLabel("passage piéton"))
        assertFalse(CrossingPrompts.isLightLabel("pedestrian crossing"))
        assertFalse(CrossingPrompts.isLightLabel("zebra crossing"))
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
            light("pedestrian traffic light", 0.60f, listOf(0f, 0f, 100f, 200f)),
            light("pedestrian traffic light", 0.91f, listOf(50f, 100f, 150f, 300f)),
        )
        assertEquals(listOf(0.25f, 0.25f, 0.75f, 0.75f), CrossingPrompts.selectLightBox(objects, 200, 400))
    }

    @Test
    fun `un feu voiture au meilleur score ne vole pas la boite`() {
        val objects = listOf(
            light("traffic light", 0.99f, listOf(0f, 0f, 100f, 200f)),
            light("pedestrian traffic light", 0.60f, listOf(50f, 100f, 150f, 300f)),
        )
        assertEquals(listOf(0.25f, 0.25f, 0.75f, 0.75f), CrossingPrompts.selectLightBox(objects, 200, 400))
    }

    @Test
    fun `un eclat au meilleur score ne vole pas la boite`() {
        val objects = listOf(
            light("pedestrian traffic light", 0.99f, listOf(0f, 0f, 4f, 6f)),
            light("pedestrian traffic light", 0.60f, listOf(50f, 100f, 150f, 300f)),
        )
        assertEquals(listOf(0.25f, 0.25f, 0.75f, 0.75f), CrossingPrompts.selectLightBox(objects, 200, 400))
    }

    @Test
    fun `les variantes feu concourent a la boite comme le wording principal`() {
        val objects = listOf(
            light("pedestrian traffic light", 0.60f, listOf(0f, 0f, 100f, 200f)),
            light("pedestrian signal", 0.91f, listOf(50f, 100f, 150f, 300f)),
        )
        assertEquals(listOf(0.25f, 0.25f, 0.75f, 0.75f), CrossingPrompts.selectLightBox(objects, 200, 400))
        val short = listOf(light("pedestrian light", 0.9f, listOf(50f, 100f, 150f, 300f)))
        assertEquals(listOf(0.25f, 0.25f, 0.75f, 0.75f), CrossingPrompts.selectLightBox(short, 200, 400))
    }

    @Test
    fun `sans feu detecte pas de boite`() {
        val objects = listOf(light("car", 0.99f, listOf(0f, 0f, 10f, 10f)))
        assertEquals(null, CrossingPrompts.selectLightBox(objects, 200, 400))
        val carsOnly = listOf(light("traffic light", 0.99f, listOf(0f, 0f, 100f, 200f)))
        assertEquals(null, CrossingPrompts.selectLightBox(carsOnly, 200, 400))
        assertEquals(null, CrossingPrompts.selectLightBox(emptyList(), 200, 400))
    }

    @Test
    fun `les wordings voiture seuls ne donnent jamais de boite, aucun faux vert`() {
        listOf("traffic light", "traffic signal", "stoplight", "feu tricolore").forEach { label ->
            val objects = listOf(light(label, 0.99f, listOf(0f, 0f, 100f, 200f)))
            assertEquals(label, null, CrossingPrompts.selectLightBox(objects, 200, 400))
        }
    }

    @Test
    fun `une boite invalide ne passe pas`() {
        val flat = listOf(light("pedestrian traffic light", 0.9f, listOf(10f, 10f, 10f, 50f)))
        assertEquals(null, CrossingPrompts.selectLightBox(flat, 200, 400))
        val short = listOf(light("pedestrian traffic light", 0.9f, listOf(10f, 10f)))
        assertEquals(null, CrossingPrompts.selectLightBox(short, 200, 400))
        val noSize = listOf(light("pedestrian traffic light", 0.9f, listOf(0f, 0f, 10f, 10f)))
        assertEquals(null, CrossingPrompts.selectLightBox(noSize, 0, 0))
    }

    private fun light(label: String, score: Float, box: List<Float>) =
        dev.sam3vision.core.model.SegObject(label, score, box, "")
}
