package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Machine anti-répétition : stabilité (2 frames), nouveauté, délai (3 s),
 * urgences (sautent le délai), premières vues (sautent la stabilité),
 * répétition bornée, marquage, reset et scène vide.
 */
class AnnouncementGateTest {

    @Test
    fun `delai par defaut de 3 secondes`() {
        assertEquals(3_000L, AnnouncementGate.DEFAULT_COOLDOWN_MS)
    }

    @Test
    fun `une frame isolee ne parle pas, deux parlent`() {
        val gate = AnnouncementGate()

        assertFalse(gate.admit(atMs = 0L, key = "personne|a 9 heures|3"))
        assertTrue(gate.admit(atMs = 500L, key = "personne|a 9 heures|3"))
    }

    @Test
    fun `cle deja dite reste muette`() {
        val gate = AnnouncementGate()
        gate.admit(atMs = 0L, key = "a")
        gate.admit(atMs = 500L, key = "a")
        gate.markSpoken("a", 500L, "Personne à 9 heures.")

        assertFalse(gate.admit(atMs = 10_000L, key = "a"))
        assertEquals("Personne à 9 heures.", gate.lastPhrase)
    }

    @Test
    fun `nouvelle cle avant le delai se tait, apres parle`() {
        val gate = AnnouncementGate(cooldownMs = 3_000L)
        gate.admit(atMs = 0L, key = "a")
        gate.admit(atMs = 100L, key = "a")
        gate.markSpoken("a", 100L, "A.")

        assertFalse(gate.admit(atMs = 3_000L, key = "b"))
        assertFalse(gate.admit(atMs = 3_099L, key = "b"))
        assertTrue(gate.admit(atMs = 3_100L, key = "b"))
    }

    @Test
    fun `urgent saute le delai mais pas la nouveaute`() {
        val gate = AnnouncementGate()
        gate.admit(atMs = 0L, key = "a")
        gate.admit(atMs = 100L, key = "a")
        gate.markSpoken("a", 100L, "A.")

        assertFalse(gate.admit(atMs = 200L, key = "b", urgent = true))
        assertTrue(gate.admit(atMs = 300L, key = "b", urgent = true))
        assertFalse(gate.admit(atMs = 10_000L, key = "a", urgent = true))
    }

    @Test
    fun `premiere vue parle des la premiere frame`() {
        val gate = AnnouncementGate()

        assertTrue(gate.admit(atMs = 0L, key = "a", firstSight = true))
    }

    @Test
    fun `repetition bornee redit apres silence seulement`() {
        val gate = AnnouncementGate()
        gate.admit(atMs = 0L, key = "gauche", firstSight = true)
        gate.markSpoken("gauche", 0L, "Gauche.")

        assertFalse(gate.admit(atMs = 1_000L, key = "gauche", repeatMs = 2_000L))
        assertTrue(gate.admit(atMs = 2_000L, key = "gauche", repeatMs = 2_000L))
    }

    @Test
    fun `sans repetition la meme cle ne reparle jamais`() {
        val gate = AnnouncementGate()
        gate.admit(atMs = 0L, key = "a", firstSight = true)
        gate.markSpoken("a", 0L, "A.")

        assertFalse(gate.admit(atMs = 60_000L, key = "a"))
    }

    @Test
    fun `marquage memorise cle instant et phrase`() {
        val gate = AnnouncementGate()
        assertNull(gate.lastPhrase)

        gate.markSpoken("a", 1_000L, "A.")

        assertEquals("A.", gate.lastPhrase)
        assertFalse(gate.admit(atMs = 2_000L, key = "a"))
    }

    @Test
    fun `reset oublie tout, la premiere annonce se redit`() {
        val gate = AnnouncementGate()
        gate.admit(atMs = 0L, key = "a", firstSight = true)
        gate.markSpoken("a", 0L, "A.")
        gate.reset()

        assertNull(gate.lastPhrase)
        assertTrue(gate.admit(atMs = 100L, key = "a", firstSight = true))
    }

    @Test
    fun `scene vide fait retomber la memoire mais pas le delai`() {
        val gate = AnnouncementGate()
        gate.admit(atMs = 0L, key = "a", firstSight = true)
        gate.markSpoken("a", 0L, "A.")
        gate.clearScene()

        // Même scène : le délai seul la retient encore.
        assertFalse(gate.admit(atMs = 1_000L, key = "a", firstSight = true))
        assertTrue(gate.admit(atMs = 3_000L, key = "a", firstSight = true))
    }
}
