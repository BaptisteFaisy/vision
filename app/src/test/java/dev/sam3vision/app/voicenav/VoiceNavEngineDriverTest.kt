package dev.sam3vision.app.voicenav

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceNavEngineDriverTest {

    @Test
    fun `seuls les virages francs ont un cap`() {
        assertEquals(SteeringCap.Left, ManeuverAction.TurnLeft.toSteeringCap())
        assertEquals(SteeringCap.Left, ManeuverAction.SlightLeft.toSteeringCap())
        assertEquals(SteeringCap.Right, ManeuverAction.TurnRight.toSteeringCap())
        assertEquals(SteeringCap.Right, ManeuverAction.SlightRight.toSteeringCap())
        assertNull(ManeuverAction.Start.toSteeringCap())
        assertNull(ManeuverAction.Continue.toSteeringCap())
        assertNull(ManeuverAction.UTurn.toSteeringCap())
        assertNull(ManeuverAction.Roundabout.toSteeringCap())
        assertNull(ManeuverAction.Arrive.toSteeringCap())
    }

    @Test
    fun `les queues suivent la chaine des manoeuvres`() {
        val maneuvers = listOf(
            NavManeuver(ManeuverAction.Start, 48.8566, 2.3522),
            NavManeuver(ManeuverAction.TurnRight, 48.8580, 2.3470),
            NavManeuver(ManeuverAction.Arrive, 48.8606, 2.3376),
        )

        val tails = tailDistances(maneuvers)

        assertEquals(3, tails.size)
        assertEquals(0.0, tails[2], 0.0)
        // ~750 m puis ~410 m à vol d'oiseau : ordre de grandeur parisien.
        assertEquals(747.0, tails[1], 80.0)
        assertEquals(tails[0], tails[1] + 412.0, 120.0)
        assertEquals(0, tailDistances(emptyList()).size)
    }

    @Test
    fun `le cap route totalise les virages francs`() {
        assertEquals(SteeringCap.Left, routeCapFor(ManeuverAction.TurnLeft))
        assertEquals(SteeringCap.Left, routeCapFor(ManeuverAction.SlightLeft))
        assertEquals(SteeringCap.Right, routeCapFor(ManeuverAction.TurnRight))
        assertEquals(SteeringCap.Right, routeCapFor(ManeuverAction.SlightRight))
        assertEquals(SteeringCap.Straight, routeCapFor(ManeuverAction.Start))
        assertEquals(SteeringCap.Straight, routeCapFor(ManeuverAction.Continue))
        assertEquals(SteeringCap.Straight, routeCapFor(ManeuverAction.UTurn))
        assertEquals(SteeringCap.Straight, routeCapFor(ManeuverAction.Roundabout))
        assertEquals(SteeringCap.Straight, routeCapFor(ManeuverAction.Arrive))
    }

    @Test
    fun `la duree restante suit l'allure pietonne`() {
        assertEquals(100L, walkSeconds(130))
        assertEquals(0L, walkSeconds(0))
        assertEquals(0L, walkSeconds(-50))
    }
}
