package dev.sam3vision.core.nav

import dev.sam3vision.core.live.GuidanceDecision
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fusion itinéraire × obstacle : libre → cap route, bouché → passage conseillé. */
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
}
