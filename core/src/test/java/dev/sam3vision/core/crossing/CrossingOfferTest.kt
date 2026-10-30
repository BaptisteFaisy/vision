package dev.sam3vision.core.crossing

import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposition de mode Traversée : étiquettes, lenteur, refus, remise à zéro. */
class CrossingOfferTest {

    private fun obj(label: String, score: Float = 0.8f) =
        SegObject(label = label, score = score, box = listOf(0f, 0f, 1f, 1f), maskPngB64 = "")

    private fun frame(vararg o: SegObject) = o.toList()

    private val walk = frame(obj("person"), obj("crosswalk"))
    private val empty = frame(obj("person"), obj("car"))

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
    fun `une detection isolee ne propose rien`() {
        val gate = CrossingOfferGate()
        assertFalse(gate.onFrame(walk, 0))
        assertFalse(gate.onFrame(empty, 100))
        assertFalse(gate.onFrame(empty, 200))
    }

    @Test
    fun `trois frames sur cinq proposent`() {
        val gate = CrossingOfferGate()
        assertFalse(gate.onFrame(walk, 0))
        assertFalse(gate.onFrame(walk, 100))
        assertTrue(gate.onFrame(walk, 200))
    }

    @Test
    fun `le passage qui sort du champ fait retomber la proposition`() {
        val gate = CrossingOfferGate()
        repeat(3) { gate.onFrame(walk, it * 100L) }
        assertTrue(gate.onFrame(walk, 300))
        gate.onFrame(empty, 400)
        gate.onFrame(empty, 500)
        assertFalse("trois frames vides ont vidé la fenêtre", gate.onFrame(empty, 600))
    }

    @Test
    fun `en mode traverse on ne propose plus`() {
        val gate = CrossingOfferGate()
        repeat(4) { assertFalse(gate.onFrame(walk, it * 100L, traversing = true)) }
    }

    @Test
    fun `un refus se tait une minute`() {
        val gate = CrossingOfferGate()
        repeat(3) { gate.onFrame(walk, it * 100L) }
        assertTrue(gate.onFrame(walk, 300))
        gate.declined(300)
        assertFalse(gate.onFrame(walk, 1_000))
        assertFalse(gate.onFrame(walk, 60_000))
        // La fenêtre a été vidée : il faut la reconstruire, pas juste attendre.
        repeat(3) { gate.onFrame(walk, 60_100L + it * 100L) }
        assertTrue("le silence est écoulé", gate.onFrame(walk, 60_400L))
    }

    @Test
    fun `reset efface la memoire et le silence`() {
        val gate = CrossingOfferGate()
        repeat(3) { gate.onFrame(walk, it * 100L) }
        gate.declined(300)
        gate.reset()
        repeat(3) { gate.onFrame(walk, 400L + it * 100L) }
        assertTrue(gate.onFrame(walk, 800))
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
