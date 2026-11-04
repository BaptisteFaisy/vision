package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priorité passage serveur / décision locale : le local d'abord. Le serveur
 * ne juge que des centres de boîte, sans mètres ni murs : son « libre » ne
 * tait jamais un bouché local, et son bouché ne s'ajoute que si le local n'a
 * rien vu, avec le côté du chemin local quand il en a un.
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
    fun `serveur libre + personne locale = local garde`() {
        val labels = listOf(at("personne", 0.5f, 2f))
        assertEquals(local(labels), resolve(labels, free))
        assertTrue(resolve(labels, free).blocked)
    }

    @Test
    fun `serveur libre + poubelle locale = local garde`() {
        val labels = listOf(at("poubelle", 0.5f, 2f))
        assertEquals(local(labels), resolve(labels, free))
        assertTrue(resolve(labels, free).blocked)
    }

    @Test
    fun `serveur bouche et mur du cote serveur = le chemin local evite le mur`() {
        // Personne à 7 m (hors de la portée proche) : le local ne bouche pas.
        val labels = listOf(
            PlacedLabel("personne", 0.62f, 0.5f, 7f, 0.60f, 0.64f),
            PlacedLabel("mur", 0.2f, 0.5f, 2f, 0.05f, 0.35f),
        )
        assertFalse(local(labels).blocked)
        val got = resolve(labels, Passage(blocked = true, side = "left"))
        assertEquals(GuidanceSide.Right, got.side)
    }
}
