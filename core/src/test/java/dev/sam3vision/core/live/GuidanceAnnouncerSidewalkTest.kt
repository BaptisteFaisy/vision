package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maintien trottoir dans l'annonceur : le retour suspend le cap destination
 * (voix), le cap reprend une fois dessus, le bouché prime toujours, l'absence
 * parle prudemment.
 */
class GuidanceAnnouncerSidewalkTest {

    private fun trottoir(x: Float) = PlacedLabel("sidewalk", x, 0.7f, null)

    private fun personne(meters: Float = 2f) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    @Test
    fun `trottoir a gauche suspend le cap et ordonne le retour`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(trottoir(0.1f))

        assertNull(announcer.onFrame(0L, labels, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Left))
        assertEquals(
            "Remonte sur le trottoir, à gauche.",
            announcer.onFrame(500L, labels, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Left),
        )
    }

    @Test
    fun `trottoir a droite ordonne le retour a droite`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(trottoir(0.9f))

        assertNull(announcer.onFrame(0L, labels, routeCap = SteeringCap.Left, sidewalk = SidewalkPosition.Right))
        assertEquals(
            "Remonte sur le trottoir, à droite.",
            announcer.onFrame(500L, labels, routeCap = SteeringCap.Left, sidewalk = SidewalkPosition.Right),
        )
    }

    @Test
    fun `cap repris une fois de retour sur le trottoir`() {
        val announcer = GuidanceAnnouncer()
        val dehors = listOf(trottoir(0.1f))
        announcer.onFrame(0L, dehors, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Left)
        assertEquals(
            "Remonte sur le trottoir, à gauche.",
            announcer.onFrame(500L, dehors, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Left),
        )

        val dessus = listOf(trottoir(0.5f))
        assertNull(announcer.onFrame(1_000L, dessus, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Ahead))
        assertEquals(
            "Va à droite.",
            announcer.onFrame(4_600L, dessus, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Ahead),
        )
    }

    @Test
    fun `trottoir devant = cap normal, le sol ne bouche pas`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(trottoir(0.5f))

        assertNull(announcer.onFrame(0L, labels, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Ahead))
        assertEquals(
            "Va à droite.",
            announcer.onFrame(500L, labels, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Ahead),
        )
    }

    @Test
    fun `obstacle prioritaire sur le retour trottoir`() {
        val announcer = GuidanceAnnouncer()
        announcer.onFrame(0L, emptyList())
        assertEquals("Continue tout droit.", announcer.onFrame(500L, emptyList()))

        val bloque = listOf(personne(2f), trottoir(0.1f))
        assertNull(announcer.onFrame(1_500L, bloque, sidewalk = SidewalkPosition.Left))
        val phrase = announcer.onFrame(2_000L, bloque, sidewalk = SidewalkPosition.Left).orEmpty()
        assertTrue("consigne obstacle attendue, reçu : $phrase", phrase.startsWith("Personne"))
        assertTrue("consigne obstacle attendue, reçu : $phrase", phrase.contains("vers la"))
    }

    @Test
    fun `trottoir absent = consigne prudente`() {
        val announcer = GuidanceAnnouncer()
        val loin = listOf(personne(10f))

        assertNull(announcer.onFrame(0L, loin, sidewalk = SidewalkPosition.Absent))
        assertEquals(
            "Trottoir introuvable, avancez lentement.",
            announcer.onFrame(500L, loin, sidewalk = SidewalkPosition.Absent),
        )
    }
}
