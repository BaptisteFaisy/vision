package dev.sam3vision.core.nav

import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.live.GuidanceDecision
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fusion itinéraire × obstacle × trottoir : bouché → passage, côté → retour, sinon cap. */
class RouteSteeringFusionTest {

    private val libre = GuidanceDecision(blocked = false, cap = SteeringCap.Straight)

    private fun bouche(passage: SteeringCap? = SteeringCap.Left) = GuidanceDecision(
        blocked = true,
        obstacle = PlacedLabel("personne", 0.5f, 0.5f, 2f),
        cap = SteeringCap.Straight,
        passage = passage,
    )

    @Test
    fun `voie libre suit le cap route`() {
        assertEquals(
            FusedSteering(SteeringCap.Left, detour = false),
            RouteSteeringFusion.fuse(SteeringCap.Left, libre),
        )
        assertEquals(
            FusedSteering(SteeringCap.Straight, detour = false),
            RouteSteeringFusion.fuse(SteeringCap.Straight, libre),
        )
        assertEquals(
            FusedSteering(SteeringCap.Right, detour = false),
            RouteSteeringFusion.fuse(SteeringCap.Right, libre),
        )
    }

    @Test
    fun `couloir bouche suit le passage en detour`() {
        val fused = RouteSteeringFusion.fuse(SteeringCap.Right, bouche(SteeringCap.Left))

        assertEquals(SteeringCap.Left, fused.direction)
        assertTrue(fused.detour)
    }

    @Test
    fun `couloir bouche sans passage garde le cap en detour`() {
        val fused = RouteSteeringFusion.fuse(SteeringCap.Right, bouche(passage = null))

        assertEquals(SteeringCap.Right, fused.direction)
        assertTrue(fused.detour)
    }

    @Test
    fun `voie libre jamais en detour`() {
        assertFalse(RouteSteeringFusion.fuse(SteeringCap.Left, libre).detour)
    }

    @Test
    fun `trottoir sur un cote suspend le cap en detour`() {
        val gauche = RouteSteeringFusion.fuse(SteeringCap.Right, libre, SidewalkPosition.Left)
        val droite = RouteSteeringFusion.fuse(SteeringCap.Left, libre, SidewalkPosition.Right)

        assertEquals(SteeringCap.Left, gauche.direction)
        assertTrue(gauche.detour)
        assertEquals(SteeringCap.Right, droite.direction)
        assertTrue(droite.detour)
    }

    @Test
    fun `obstacle prioritaire sur le retour trottoir`() {
        val fused = RouteSteeringFusion.fuse(SteeringCap.Right, bouche(SteeringCap.Left), SidewalkPosition.Right)

        assertEquals(SteeringCap.Left, fused.direction)
        assertTrue(fused.detour)
    }

    @Test
    fun `trottoir devant ou absent garde le cap route`() {
        assertEquals(
            FusedSteering(SteeringCap.Right, detour = false),
            RouteSteeringFusion.fuse(SteeringCap.Right, libre, SidewalkPosition.Ahead),
        )
        assertEquals(
            FusedSteering(SteeringCap.Right, detour = false),
            RouteSteeringFusion.fuse(SteeringCap.Right, libre, SidewalkPosition.Absent),
        )
    }
}
