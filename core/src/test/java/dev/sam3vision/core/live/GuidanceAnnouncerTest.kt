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
    fun `obstacle parle des la premiere frame sans attendre le delai`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        announcer.onFrame(0L, libre)
        assertEquals("Continue.", announcer.onFrame(500L, libre))
        // le danger arrive 1 s après : parlé aussitôt, pas de stabilité ni de délai
        assertEquals(
            "Va à gauche, 2 mètres.",
            announcer.onFrame(1_500L, listOf(personne(2f))),
        )
        // même clé : nouveauté, silence
        assertNull(announcer.onFrame(2_000L, listOf(personne(2f))))
    }

    @Test
    fun `retour au libre respecte delai et stabilite`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("Va à gauche, 2 mètres.", announcer.onFrame(0L, listOf(personne(2f))))
        assertNull(announcer.onFrame(500L, listOf(personne(2f))))
        // le couloir se dégage aussitôt : la réassurance attend stabilité + délai
        assertNull(announcer.onFrame(1_000L, libre))
        assertNull(announcer.onFrame(1_500L, libre))
        assertEquals("Continue.", announcer.onFrame(4_600L, libre))
    }

    @Test
    fun `obstacle qui s'approche frame par frame parle a chaque seau`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("Va à gauche, 3 mètres.", announcer.onFrame(0L, listOf(personne(3.4f))))
        assertEquals("Va à gauche, 2 mètres.", announcer.onFrame(500L, listOf(personne(2.4f))))
        assertEquals("Va à gauche, 1 mètre.", announcer.onFrame(1_000L, listOf(personne(1.4f))))
        assertEquals("Va à gauche, 40 centimètres.", announcer.onFrame(1_500L, listOf(personne(0.4f))))
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
    fun `contournement proche parle des la premiere frame`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun proche(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

        assertEquals("Va à gauche, 40 centimètres.", announcer.onFrame(0L, listOf(proche(0.4f))))
        // même seau, même contournement : rien de neuf
        assertNull(announcer.onFrame(500L, listOf(proche(0.45f))))
    }

    @Test
    fun `reset oublie la decision`() {
        val announcer = GuidanceAnnouncer()
        announcer.onFrame(0L, listOf(personne(2f)))

        announcer.reset()

        assertNull(announcer.lastDecision)
    }

    @Test
    fun `matiere proche non mesuree guide des la premiere frame`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun matiere() = PlacedLabel("mur", 0.5f, 0.5f, null)

        assertEquals("Va à gauche.", announcer.onFrame(0L, listOf(matiere())))
        assertNull(announcer.onFrame(500L, listOf(matiere())))
    }
}
