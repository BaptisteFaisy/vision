package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Déclencheur : première vue, non-répétition, délai minimal, retour des objets. */
class PositionAnnouncerTest {

    private val gauche = PlacedLabel("personne", 0.1f, 0.5f)
    private val droite = PlacedLabel("chien", 0.9f, 0.5f)

    @Test
    fun `scene nouvelle parlee des la premiere frame puis silence`() {
        val announcer = PositionAnnouncer()

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(0L, listOf(gauche)))
        assertNull(announcer.onFrame(500L, listOf(gauche)))
        assertNull(announcer.onFrame(10_000L, listOf(gauche)))
    }

    @Test
    fun `nouvel objet annonce apres le delai`() {
        val announcer = PositionAnnouncer(cooldownMs = 4_000L)

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(0L, listOf(gauche)))
        // nouvel objet : la scène change, mais le délai n'est pas écoulé
        assertNull(announcer.onFrame(1_000L, listOf(gauche, droite)))
        assertEquals(
            "Chien at 3 o'clock, personne at 9 o'clock.",
            announcer.onFrame(4_000L, listOf(gauche, droite)),
        )
        assertNull(announcer.onFrame(4_600L, listOf(gauche, droite)))
    }

    @Test
    fun `objet qui change d'heure est reannonce`() {
        val announcer = PositionAnnouncer(cooldownMs = 1_000L)

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(0L, listOf(gauche)))
        assertNull(announcer.onFrame(500L, listOf(gauche)))
        val phrase = announcer.onFrame(1_500L, listOf(PlacedLabel("personne", 0.9f)))

        assertEquals("Personne at 3 o'clock.", phrase)
        assertNull(announcer.onFrame(2_000L, listOf(PlacedLabel("personne", 0.9f))))
    }

    @Test
    fun `scene vide oublie la memoire de scene`() {
        val announcer = PositionAnnouncer()

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(0L, listOf(gauche)))
        assertNull(announcer.onFrame(500L, listOf(gauche)))
        assertNull(announcer.onFrame(9_000L, emptyList()))

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(9_500L, listOf(gauche)))
    }

    @Test
    fun `reset autorise une nouvelle annonce immediate`() {
        val announcer = PositionAnnouncer()

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(0L, listOf(gauche)))
        announcer.reset()

        assertEquals("Personne at 9 o'clock.", announcer.onFrame(600L, listOf(gauche)))
        assertNull(announcer.onFrame(700L, listOf(gauche)))
    }

    @Test
    fun `objet qui s'approche est reannonce au metre pres sans attendre`() {
        val announcer = PositionAnnouncer(cooldownMs = 1_000L)
        fun personne(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters = meters)

        assertEquals("Personne 3 meters away.", announcer.onFrame(0L, listOf(personne(3.4f))))
        // même mètre : rien de neuf à dire
        assertNull(announcer.onFrame(500L, listOf(personne(3.1f))))
        // elle s'est rapprochée d'un mètre : parlé aussitôt, délai écoulé
        assertEquals("Personne 2 meters away.", announcer.onFrame(1_500L, listOf(personne(2.4f))))
        // encore plus près, délai écoulé : parlé aussitôt
        assertEquals("Personne 1 meter away.", announcer.onFrame(2_500L, listOf(personne(1.4f))))
    }

    @Test
    fun `tremble de mesure ne reannonce pas, vraie approche oui`() {
        val announcer = PositionAnnouncer(cooldownMs = 1_000L)
        fun personne(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters = meters)

        assertEquals("Personne 3 meters away.", announcer.onFrame(0L, listOf(personne(3.4f))))
        // Tremblé autour de la frontière 2,5 m : même seau, silence.
        assertNull(announcer.onFrame(1_500L, listOf(personne(2.51f))))
        assertNull(announcer.onFrame(3_000L, listOf(personne(2.49f))))
        // Vraie approche : nouveau seau, parlé.
        assertEquals("Personne 2 meters away.", announcer.onFrame(4_500L, listOf(personne(2.4f))))
    }

    @Test
    fun `phrase retenue pour l affichage`() {
        val announcer = PositionAnnouncer()

        announcer.onFrame(0L, listOf(gauche))

        assertEquals("Personne at 9 o'clock.", announcer.lastPhrase)
    }
}
