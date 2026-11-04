package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priorité passage serveur / décision locale : le serveur ([Passage]) juge
 * les bloqueurs à pleine résolution et remplace le local — sauf quand il
 * dit « libre » alors qu'un mur, une barrière ou un poteau bouche le
 * couloir localement : le local reprend la main, avec le côté de
 * [Guidance.decide]. Les personnes restent jugées par le serveur.
 */
class GuidanceResolverTest {

    private val hfov = LensSpec.DEFAULT_H_FOV_DEG
    private val range = Guidance.RANGE_METERS
    private val free = Passage(blocked = false)

    private fun resolve(labels: List<PlacedLabel>, passage: Passage?): GuidanceDecision =
        resolveDecision(
            labels, SteeringCap.Straight, range, hfov,
            free = FreeSpace.unknown(), speedMps = null, holeSide = null,
            samPassage = passage,
        )

    /** Décision locale rejouée à l'identique (mêmes arguments que le resolver). */
    private fun local(labels: List<PlacedLabel>): GuidanceDecision =
        Guidance.decide(
            labels, SteeringCap.Straight, range, hfov,
            free = FreeSpace.unknown(), speedMps = null, holeSide = null,
        )

    private fun at(label: String, centerX: Float, meters: Float): PlacedLabel =
        PlacedLabel(label = label, centerX = centerX, meters = meters)

    @Test
    fun `serveur libre + barriere locale = local garde`() {
        val labels = listOf(at("barrière", 0.5f, 2f))
        val got = resolve(labels, free)
        assertEquals(local(labels), got)
        assertTrue(got.blocked)
        assertEquals("barrière", got.obstacle?.label)
    }

    @Test
    fun `serveur libre + poteau local mesure = local garde`() {
        val labels = listOf(at("poteau", 0.5f, 2f))
        val got = resolve(labels, free)
        assertEquals(local(labels), got)
        assertTrue(got.blocked)
        assertEquals("poteau", got.obstacle?.label)
    }

    @Test
    fun `serveur libre + mur local = local garde`() {
        val labels = listOf(at("mur", 0.5f, 2f))
        val got = resolve(labels, free)
        assertEquals(local(labels), got)
        assertTrue(got.blocked)
    }

    @Test
    fun `serveur libre + voie libre = libre`() {
        assertFalse(resolve(emptyList(), free).blocked)
    }

    @Test
    fun `serveur bouche = cote serveur`() {
        val got = resolve(emptyList(), Passage(blocked = true, side = "right"))
        assertTrue(got.blocked)
        assertEquals(GuidanceSide.Right, got.side)
    }

    @Test
    fun `serveur bouche cote sans 1m = bascule cote sur`() {
        val labels = listOf(at("personne", 0.75f, 2f))
        val got = resolve(labels, Passage(blocked = true, side = "right"))
        assertTrue(got.blocked)
        assertEquals(GuidanceSide.Left, got.side)
    }

    @Test
    fun `backend anterieur = decision locale`() {
        val labels = listOf(at("barrière", 0.5f, 2f))
        assertEquals(local(labels), resolve(labels, null))
    }

    @Test
    fun `serveur libre + personne locale = serveur gagne`() {
        val labels = listOf(at("personne", 0.5f, 2f))
        assertTrue(local(labels).blocked)
        assertFalse(resolve(labels, free).blocked)
    }
}
