package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Déclencheur du guidage : stabilité, non-répétition, danger prioritaire sur le délai. */
class GuidanceAnnouncerTest {

    private val libre = emptyList<PlacedLabel>()
    private fun personne(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    @Test
    fun `une frame isolee ne declenche rien`() {
        val announcer = GuidanceAnnouncer()

        assertNull(announcer.onFrame(0L, libre))
    }

    @Test
    fun `voie libre annoncee une fois puis silence`() {
        val announcer = GuidanceAnnouncer()

        assertNull(announcer.onFrame(0L, libre))
        assertEquals("Continue.", announcer.onFrame(500L, libre))
        assertNull(announcer.onFrame(10_000L, libre))
    }

    @Test
    fun `obstacle parle sans attendre le delai`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        announcer.onFrame(0L, libre)
        assertEquals("Continue.", announcer.onFrame(500L, libre))
        // le danger arrive 1 s après : stabilité d'abord, mais pas d'attente du délai
        assertNull(announcer.onFrame(1_500L, listOf(personne(2f))))
        assertEquals(
            "Va à gauche, 2 mètres.",
            announcer.onFrame(2_000L, listOf(personne(2f))),
        )
    }

    @Test
    fun `retour au libre respecte le delai`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        announcer.onFrame(0L, listOf(personne(2f)))
        announcer.onFrame(500L, listOf(personne(2f)))
        // le couloir se dégage aussitôt : la réassurance attend le délai
        assertNull(announcer.onFrame(1_000L, libre))
        assertNull(announcer.onFrame(1_500L, libre))
        assertEquals("Continue.", announcer.onFrame(4_600L, libre))
    }

    @Test
    fun `obstacle qui s'approche est reannonce`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 1_000L)

        announcer.onFrame(0L, listOf(personne(3.4f)))
        assertEquals(
            "Va à gauche, 3 mètres.",
            announcer.onFrame(500L, listOf(personne(3.4f))),
        )
        assertNull(announcer.onFrame(2_000L, listOf(personne(2.4f))))
        assertEquals(
            "Va à gauche, 2 mètres.",
            announcer.onFrame(2_500L, listOf(personne(2.4f))),
        )
    }

    @Test
    fun `reset autorise une nouvelle annonce immediate`() {
        val announcer = GuidanceAnnouncer()

        announcer.onFrame(0L, libre)
        announcer.onFrame(500L, libre)
        announcer.reset()
        assertNull(announcer.onFrame(600L, libre))

        assertEquals("Continue.", announcer.onFrame(700L, libre))
    }

    @Test
    fun `phrase retenue pour l affichage`() {
        val announcer = GuidanceAnnouncer()

        announcer.onFrame(0L, libre)
        announcer.onFrame(500L, libre)

        assertEquals("Continue.", announcer.lastPhrase)
    }

    @Test
    fun `cap route prime sur le cap manuel`() {
        val announcer = GuidanceAnnouncer()

        assertNull(announcer.onFrame(0L, libre, cap = SteeringCap.Left, routeCap = SteeringCap.Right))
        assertEquals(
            "Va à droite.",
            announcer.onFrame(1_000L, libre, cap = SteeringCap.Left, routeCap = SteeringCap.Right),
        )
    }

    @Test
    fun `derniere decision exposee pour la video`() {
        val announcer = GuidanceAnnouncer()
        assertNull(announcer.lastDecision)

        announcer.onFrame(0L, listOf(personne(2f)))

        val decision = announcer.lastDecision
        assertTrue("décision attendue", decision != null)
        assertEquals(true, decision?.blocked)
        assertEquals(SteeringCap.Left, decision?.passage)
    }

    @Test
    fun `stop tres proche parle apres stabilite`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun proche(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

        assertNull(announcer.onFrame(0L, listOf(proche(0.4f))))
        assertEquals("Stop.", announcer.onFrame(500L, listOf(proche(0.4f))))
        // même seau, toujours stop : rien de neuf
        assertNull(announcer.onFrame(1_000L, listOf(proche(0.45f))))
    }

    @Test
    fun `reset oublie la decision`() {
        val announcer = GuidanceAnnouncer()
        announcer.onFrame(0L, listOf(personne(2f)))

        announcer.reset()

        assertNull(announcer.lastDecision)
    }
}
