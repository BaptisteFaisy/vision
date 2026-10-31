package dev.sam3vision.core.live

import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Déclencheur du guidage : un mot par changement, dit aussitôt, jamais répété. */
class GuidanceAnnouncerTest {

    private val libre = emptyList<PlacedLabel>()
    private fun personne(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    @Test
    fun `voie libre parle des la premiere frame`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
    }

    @Test
    fun `voie libre annoncee une fois puis silence`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        assertNull(announcer.onFrame(500L, libre))
    }

    @Test
    fun `obstacle parle des la premiere frame sans attendre`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        // le danger arrive 1 s après : voix TwinLite aussitôt, pas de stabilité ni de délai
        assertEquals("left.", announcer.onFrame(1_000L, listOf(personne(2f))))
        // même mot : nouveauté, silence
        assertNull(announcer.onFrame(1_200L, listOf(personne(2f))))
    }

    @Test
    fun `retour au libre parle aussitot`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("left.", announcer.onFrame(0L, listOf(personne(2f))))
        assertNull(announcer.onFrame(200L, listOf(personne(2f))))
        // le couloir se dégage aussitôt : le mot change, parlé sans délai
        assertEquals("Straight.", announcer.onFrame(400L, libre))
    }

    @Test
    fun `seul le changement de mot fait reparler`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("left.", announcer.onFrame(0L, listOf(personne(3.4f))))
        assertNull(announcer.onFrame(200L, listOf(personne(2.4f))))
        assertNull(announcer.onFrame(400L, listOf(personne(1.4f))))
        assertEquals("obstacle, stop.", announcer.onFrame(600L, listOf(personne(0.4f))))
    }

    @Test
    fun `reset autorise une nouvelle annonce immediate`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        announcer.reset()

        assertEquals("Straight.", announcer.onFrame(200L, libre))
    }

    @Test
    fun `phrase retenue pour l affichage`() {
        val announcer = GuidanceAnnouncer()

        announcer.onFrame(0L, libre)

        assertEquals("Straight.", announcer.lastPhrase)
    }

    @Test
    fun `cap route prime sur le cap manuel`() {
        val announcer = GuidanceAnnouncer()

        assertEquals(
            "Right.",
            announcer.onFrame(0L, libre, cap = SteeringCap.Left, routeCap = SteeringCap.Right),
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
    fun `contact dit obstacle TwinLite des la premiere frame`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun proche(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

        assertEquals("obstacle, stop.", announcer.onFrame(0L, listOf(proche(0.4f))))
        // même mot : rien de neuf
        assertNull(announcer.onFrame(200L, listOf(proche(0.45f))))
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

        assertEquals("left.", announcer.onFrame(0L, listOf(matiere())))
        assertNull(announcer.onFrame(200L, listOf(matiere())))
    }

    private fun personneDroite(meters: Float) = PlacedLabel("personne", 0.7f, 0.5f, meters)

    @Test
    fun `personne qui s approche a droite dit gauche`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, listOf(personneDroite(3.0f))))
        assertNull(announcer.onFrame(250L, listOf(personneDroite(2.6f))))
        assertEquals("Left.", announcer.onFrame(500L, listOf(personneDroite(2.2f))))
    }

    @Test
    fun `poteau qui s approche a droite dit gauche`() {
        val announcer = GuidanceAnnouncer()
        fun poteauDroite(meters: Float) = PlacedLabel("poteau", 0.7f, 0.5f, meters)

        assertEquals("Tout droit.", announcer.onFrame(0L, listOf(poteauDroite(3.0f))))
        assertNull(announcer.onFrame(250L, listOf(poteauDroite(2.6f))))
        assertEquals("Gauche.", announcer.onFrame(500L, listOf(poteauDroite(2.2f))))
    }

    @Test
    fun `couloir bouche prime sur le decalage`() {
        val announcer = GuidanceAnnouncer()
        fun bloque(m: Float) = PlacedLabel("personne", 0.5f, 0.5f, m)

        assertEquals(
            "left.",
            announcer.onFrame(0L, listOf(bloque(3.4f), personneDroite(3.0f))),
        )
        assertNull(announcer.lastNudge)
    }

    @Test
    fun `decalage stable ne reparle pas`() {
        val announcer = GuidanceAnnouncer()

        announcer.onFrame(0L, listOf(personneDroite(3.0f)))
        announcer.onFrame(250L, listOf(personneDroite(2.6f)))
        assertEquals("Left.", announcer.onFrame(500L, listOf(personneDroite(2.2f))))
        assertNull(announcer.onFrame(750L, listOf(personneDroite(1.8f))))
        assertNull(announcer.onFrame(1_000L, listOf(personneDroite(1.4f))))
    }

    @Test
    fun `arbitrage bloque TwinLite puis libre SAM, une seule phrase par changement`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        val bloque = listOf(personne(2f))

        assertEquals("left.", announcer.onFrame(0L, bloque))
        assertNull(announcer.onFrame(200L, bloque))
        assertEquals("Straight.", announcer.onFrame(400L, libre))
        assertNull(announcer.onFrame(600L, libre))
        assertEquals("left.", announcer.onFrame(800L, bloque))
        assertNull(announcer.onFrame(1_000L, bloque))
    }

    @Test
    fun `pas de doublon, cote de sortie seul sans arret devant`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        // bouché à 2 m : le côté parle seul, jamais « obstacle » + côté ensemble
        assertEquals("left.", announcer.onFrame(0L, listOf(personne(2f))))
        // contact ensuite : l'arrêt remplace le côté, une seule phrase aussi
        assertEquals("obstacle, stop.", announcer.onFrame(200L, listOf(personne(0.4f))))
        assertNull(announcer.onFrame(400L, listOf(personne(0.4f))))
    }

    @Test
    fun `contact anglais dit obstacle stop`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals(
            "obstacle, stop.",
            announcer.onFrame(0L, listOf(personne(0.4f)), SpeechLanguage.En),
        )
        assertNull(announcer.onFrame(200L, listOf(personne(0.45f)), SpeechLanguage.En))
    }

    @Test
    fun `passage SAM bouche parle son cote`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true, side = "right", label = "person")
        val bloque = listOf(personne(2f))

        assertEquals("right.", announcer.onFrame(0L, bloque, samPassage = sam))
        assertNull(announcer.onFrame(200L, bloque, samPassage = sam))
    }

    @Test
    fun `passage SAM libre tait un couloir localement bouche`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = false)

        assertEquals("Straight.", announcer.onFrame(0L, listOf(personne(2f)), samPassage = sam))
    }

    @Test
    fun `passage SAM bouche avec mesure proche dit stop`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true, side = "left", label = "person")

        assertEquals(
            "obstacle, stop.",
            announcer.onFrame(0L, listOf(personne(0.4f)), samPassage = sam),
        )
    }

    @Test
    fun `passage SAM bouche sans cote passe a gauche`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true)

        assertEquals("left.", announcer.onFrame(0L, listOf(personne(2f)), samPassage = sam))
    }
}
