package dev.sam3vision.core.crossing

import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Armement auto ≤ 2 m : étiquettes, proximité, lenteur, refus, remise à zéro. */
class CrossingOfferTest {

    private val h = 1138
    private val midBox = listOf(0f, 100f, 100f, 500f)
    private val underBox = listOf(0f, 900f, 512f, 1137f)

    private fun obj(label: String, score: Float = 0.8f, box: List<Float> = midBox) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    private fun frame(vararg o: SegObject) = o.toList()

    private val walk = frame(obj("person"), obj("crosswalk"))
    private val empty = frame(obj("person"), obj("car"))

    private val near = { _: SegObject -> 1.5f }
    private val edge = { _: SegObject -> 2.0f }
    private val far = { _: SegObject -> 5.0f }
    private val none = { _: SegObject -> null }
    private val nan = { _: SegObject -> Float.NaN }

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
    fun `un score trop faible ne compte pas`() {
        assertTrue(CrossingOffer.seesCrosswalk(frame(obj("crosswalk", 0.8f))))
        assertFalse(CrossingOffer.seesCrosswalk(frame(obj("crosswalk", 0.2f))))
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
    fun `une detection isolee ne propose rien`() {
        val gate = CrossingOfferGate()
        assertFalse(gate.onFrame(walk, 0, metersOf = near, frameH = h))
        assertFalse(gate.onFrame(empty, 100, metersOf = near, frameH = h))
        assertFalse(gate.onFrame(empty, 200, metersOf = near, frameH = h))
    }

    @Test
    fun `trois frames sur cinq proposent`() {
        val gate = CrossingOfferGate()
        assertFalse(gate.onFrame(walk, 0, metersOf = near, frameH = h))
        assertFalse(gate.onFrame(walk, 100, metersOf = near, frameH = h))
        assertTrue(gate.onFrame(walk, 200, metersOf = near, frameH = h))
    }

    @Test
    fun `le passage qui sort du champ fait retomber la proposition`() {
        val gate = CrossingOfferGate()
        repeat(3) { gate.onFrame(walk, it * 100L, metersOf = near, frameH = h) }
        assertTrue(gate.onFrame(walk, 300, metersOf = near, frameH = h))
        gate.onFrame(empty, 400, metersOf = near, frameH = h)
        gate.onFrame(empty, 500, metersOf = near, frameH = h)
        assertFalse("trois frames vides ont vidé la fenêtre", gate.onFrame(empty, 600, metersOf = near, frameH = h))
    }

    @Test
    fun `en mode traverse on ne propose plus`() {
        val gate = CrossingOfferGate()
        repeat(4) { assertFalse(gate.onFrame(walk, it * 100L, traversing = true, metersOf = near, frameH = h)) }
    }

    @Test
    fun `un refus se tait une minute`() {
        val gate = CrossingOfferGate()
        repeat(3) { gate.onFrame(walk, it * 100L, metersOf = near, frameH = h) }
        assertTrue(gate.onFrame(walk, 300, metersOf = near, frameH = h))
        gate.declined(300)
        assertFalse(gate.onFrame(walk, 1_000, metersOf = near, frameH = h))
        assertFalse(gate.onFrame(walk, 60_000, metersOf = near, frameH = h))
        // La fenêtre a été vidée : il faut la reconstruire, pas juste attendre.
        repeat(3) { gate.onFrame(walk, 60_100L + it * 100L, metersOf = near, frameH = h) }
        assertTrue("le silence est écoulé", gate.onFrame(walk, 60_400L, metersOf = near, frameH = h))
    }

    @Test
    fun `reset efface la memoire et le silence`() {
        val gate = CrossingOfferGate()
        repeat(3) { gate.onFrame(walk, it * 100L, metersOf = near, frameH = h) }
        gate.declined(300)
        gate.reset()
        repeat(3) { gate.onFrame(walk, 400L + it * 100L, metersOf = near, frameH = h) }
        assertTrue(gate.onFrame(walk, 800, metersOf = near, frameH = h))
    }

    @Test
    fun `un passage lointain n arme jamais`() {
        val gate = CrossingOfferGate()
        repeat(5) { assertFalse(gate.onFrame(walk, it * 100L, metersOf = far, frameH = h)) }
    }

    @Test
    fun `un passage a 2 m pile arme`() {
        val gate = CrossingOfferGate()
        assertFalse(gate.onFrame(walk, 0, metersOf = edge, frameH = h))
        assertFalse(gate.onFrame(walk, 100, metersOf = edge, frameH = h))
        assertTrue(gate.onFrame(walk, 200, metersOf = edge, frameH = h))
    }

    @Test
    fun `sans mesure le passage sous les pieds arme`() {
        val gate = CrossingOfferGate()
        val under = frame(obj("person"), obj("crosswalk", box = underBox))
        assertFalse(gate.onFrame(under, 0, metersOf = none, frameH = h))
        assertFalse(gate.onFrame(under, 100, metersOf = none, frameH = h))
        assertTrue(gate.onFrame(under, 200, metersOf = none, frameH = h))
    }

    @Test
    fun `sans mesure le passage loin reste silencieux`() {
        val gate = CrossingOfferGate()
        repeat(4) { assertFalse(gate.onFrame(walk, it * 100L, metersOf = none, frameH = h)) }
    }

    @Test
    fun `une mesure invalide reste silencieuse`() {
        val gate = CrossingOfferGate()
        repeat(4) { assertFalse(gate.onFrame(walk, it * 100L, metersOf = nan, frameH = h)) }
    }

    @Test
    fun `deux proches et trois loin ne proposent pas`() {
        val gate = CrossingOfferGate()
        val meters = listOf(near, far, near, far, far)
        meters.forEachIndexed { i, m -> assertFalse(gate.onFrame(walk, i * 100L, metersOf = m, frameH = h)) }
    }

    @Test
    fun `un score trop faible ne compte pas meme a moins de 2 m`() {
        val gate = CrossingOfferGate()
        val weak = frame(obj("crosswalk", 0.2f))
        repeat(4) { assertFalse(gate.onFrame(weak, it * 100L, metersOf = near, frameH = h)) }
    }

    @Test
    fun `quatre frames vides de suite font retomber le mode auto`() {
        val gate = CrossingLossGate()
        assertTrue(gate.onFrame(false))
        assertTrue(gate.onFrame(false))
        assertTrue(gate.onFrame(false))
        assertFalse("4e frame vide : retour au mode normal", gate.onFrame(false))
    }

    @Test
    fun `une reapparition remet le compteur de perte a zero`() {
        val gate = CrossingLossGate()
        gate.onFrame(false)
        gate.onFrame(false)
        gate.onFrame(false)
        assertTrue(gate.onFrame(true))
        assertTrue(gate.onFrame(false))
        assertTrue(gate.onFrame(false))
        assertTrue(gate.onFrame(false))
        assertFalse(gate.onFrame(false))
    }

    @Test
    fun `reset efface les frames vides accumulees`() {
        val gate = CrossingLossGate()
        gate.onFrame(false)
        gate.onFrame(false)
        gate.onFrame(false)
        gate.reset()
        assertTrue(gate.onFrame(false))
        assertTrue(gate.onFrame(false))
        assertTrue(gate.onFrame(false))
        assertFalse(gate.onFrame(false))
    }
}
