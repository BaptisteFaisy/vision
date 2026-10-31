package dev.sam3vision.core.crossing

import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proximité prouvée d'un passage piéton : étiquettes et distance (guidage latéral). */
class CrossingOfferTest {

    private val h = 1138
    private val midBox = listOf(0f, 100f, 100f, 500f)

    private fun obj(label: String, score: Float = 0.8f, box: List<Float> = midBox) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    private fun frame(vararg o: SegObject) = o.toList()

    private val walk = frame(obj("person"), obj("crosswalk"))

    @Test
    fun `les etiquettes de passage sont reconnues telles que SAM les rend`() {
        assertTrue(CrossingOffer.isCrosswalkLabel("crosswalk"))
        assertTrue(CrossingOffer.isCrosswalkLabel("Crosswalk"))
        assertTrue(CrossingOffer.isCrosswalkLabel("zebra crossing"))
        assertTrue(CrossingOffer.isCrosswalkLabel("passage piéton"))
        assertTrue(CrossingOffer.isCrosswalkLabel("Passage Pieton"))
        assertFalse(CrossingOffer.isCrosswalkLabel("sidewalk"))
        assertFalse(CrossingOffer.isCrosswalkLabel("crossing"))
        assertFalse(CrossingOffer.isCrosswalkLabel("person"))
    }

    @Test
    fun `un passage mesure a 2 m ou moins compte, au-dela non`() {
        val at = { m: Float -> { _: SegObject -> m } }
        assertTrue(CrossingOffer.seesNearCrosswalk(walk, at(1.99f), h))
        assertTrue(CrossingOffer.seesNearCrosswalk(walk, at(2.0f), h))
        assertFalse(CrossingOffer.seesNearCrosswalk(walk, at(2.01f), h))
        assertFalse(CrossingOffer.seesNearCrosswalk(walk, at(5.0f), h))
    }

    @Test
    fun `un score trop faible ne compte pas meme a moins de 2 m`() {
        val weak = frame(obj("crosswalk", 0.2f))
        assertFalse(CrossingOffer.seesNearCrosswalk(weak, { 1.5f }, h))
    }

    @Test
    fun `le guidage traverse voit a 3 m, pas au-dela`() {
        val at = { m: Float -> { _: SegObject -> m } }
        val max = CrossingGuidance.GUIDE_METERS
        assertTrue(CrossingOffer.seesNearCrosswalk(walk, at(2.99f), h, maxMeters = max))
        assertTrue(CrossingOffer.seesNearCrosswalk(walk, at(3.0f), h, maxMeters = max))
        assertFalse(CrossingOffer.seesNearCrosswalk(walk, at(3.01f), h, maxMeters = max))
    }
}
