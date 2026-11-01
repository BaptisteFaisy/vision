package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Maintien trottoir dans l'annonceur : le retour suspend le cap destination
 * (voix), le cap reprend une fois dessus, le bouché prime toujours, l'absence
 * garde le cap — le tout en un mot, dit aussitôt.
 */
class GuidanceAnnouncerSidewalkTest {

    private fun trottoir(x: Float) = PlacedLabel("sidewalk", x, 0.7f, null)

    private fun personne(meters: Float = 2f) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    @Test
    fun `trottoir a gauche suspend le cap et ordonne le retour`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(trottoir(0.1f))

        assertEquals(
            "Left.",
            announcer.onFrame(0L, labels, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Left),
        )
    }

    @Test
    fun `trottoir a droite ordonne le retour a droite`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(trottoir(0.9f))

        assertEquals(
            "Right.",
            announcer.onFrame(0L, labels, routeCap = SteeringCap.Left, sidewalk = SidewalkPosition.Right),
        )
    }

    @Test
    fun `cap repris une fois de retour sur le trottoir`() {
        val announcer = GuidanceAnnouncer()
        val dehors = listOf(trottoir(0.1f))
        assertEquals(
            "Left.",
            announcer.onFrame(0L, dehors, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Left),
        )

        val dessus = listOf(trottoir(0.5f))
        assertEquals(
            "Right.",
            announcer.onFrame(500L, dessus, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Ahead),
        )
    }

    @Test
    fun `trottoir devant = cap normal, le sol ne bouche pas`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(trottoir(0.5f))

        assertEquals(
            "Right.",
            announcer.onFrame(0L, labels, routeCap = SteeringCap.Right, sidewalk = SidewalkPosition.Ahead),
        )
    }

    @Test
    fun `obstacle prioritaire sur le retour trottoir`() {
        val announcer = GuidanceAnnouncer()
        assertEquals("Straight.", announcer.onFrame(0L, emptyList()))

        val bloque = listOf(personne(2f), trottoir(0.1f))
        assertEquals("left.", announcer.onFrame(500L, bloque, sidewalk = SidewalkPosition.Left))
        assertNull(announcer.onFrame(1_000L, bloque, sidewalk = SidewalkPosition.Left))
    }

    @Test
    fun `trottoir absent garde le cap`() {
        val announcer = GuidanceAnnouncer()
        val loin = listOf(personne(10f))

        assertEquals("Straight.", announcer.onFrame(0L, loin, sidewalk = SidewalkPosition.Absent))
    }

    @Test
    fun `retour trottoir vers une personne proche devie`() {
        // Nudge prioritaire sur le retour trottoir : à 1 m, la personne
        // proche conseille dès la 1re frame (audit 31/10), on s'écarte.
        val announcer = GuidanceAnnouncer()
        val proche = listOf(PlacedLabel("personne", 0.3f, 0.5f, 1f))

        assertEquals("Right.", announcer.onFrame(0L, proche, sidewalk = SidewalkPosition.Left))
    }

    @Test
    fun `retour trottoir cote degage parle quand meme`() {
        val announcer = GuidanceAnnouncer()
        val loin = listOf(PlacedLabel("personne", 0.05f, 0.5f, 4f))

        assertEquals("Left.", announcer.onFrame(0L, loin, sidewalk = SidewalkPosition.Left))
    }
}
