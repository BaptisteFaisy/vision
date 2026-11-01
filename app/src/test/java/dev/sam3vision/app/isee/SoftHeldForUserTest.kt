package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phrase douce d'une frame quand le user parle : l'alerte de sécurité retenue
 * ne part pas en voix de sécurité (elle couperait le micro) mais rejoint le
 * doux, derrière la phrase de la frame — elle sera dite au silence. Sans
 * parole du user, la décision de l'arbitre passe telle quelle.
 */
class SoftHeldForUserTest {

    @Test
    fun `sans user, la decision passe telle quelle`() {
        val decision = ObstacleDecision(safety = "Bordure à 0,5 mètre.", soft = "Personne à 2 mètres.")

        assertEquals("Personne à 2 mètres.", softOf(decision, heldForUser = false))
    }

    @Test
    fun `user qui parle, securite derriere le doux`() {
        val decision = ObstacleDecision(safety = "Bordure à 0,5 mètre.", soft = "Personne à 2 mètres.")

        assertEquals("Personne à 2 mètres. Bordure à 0,5 mètre.", softOf(decision, heldForUser = true))
    }

    @Test
    fun `user qui parle, securite seule retenue`() {
        val decision = ObstacleDecision(safety = "Bordure à 0,5 mètre.", soft = null)

        assertEquals("Bordure à 0,5 mètre.", softOf(decision, heldForUser = true))
    }

    @Test
    fun `rien a dire, rien de retenu`() {
        val decision = ObstacleDecision(safety = null, soft = null)

        assertNull(softOf(decision, heldForUser = true))
    }
}
