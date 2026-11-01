package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadence des « Attention … » : même annonce jamais deux fois dans la
 * fenêtre (le cadran qui tremble ne fait pas une nouvelle phrase), silence
 * entre deux annonces, urgence qui passe le silence mais garde le sien, et
 * rien d'autre que la famille « Attention » ne consomme le budget.
 */
class AttentionCadenceTest {

    private fun cadence() = AttentionCadence(gapMs = 6_000L, repeatMs = 20_000L, urgentGapMs = 2_000L)

    @Test
    fun `phrase sans attention jamais bloquee`() {
        val cadence = cadence()

        assertFalse(cadence.blocked(VOITURE, 0L))
        assertFalse(cadence.blocked(VOITURE, 10L))
    }

    @Test
    fun `meme annonce bloquee dans la fenetre puis liberee`() {
        val cadence = cadence()
        cadence.record(BARRIERE, 0L)

        assertTrue(cadence.blocked(BARRIERE, 1_000L))
        assertFalse(cadence.blocked(BARRIERE, 20_000L))
    }

    @Test
    fun `heure qui change, meme annonce`() {
        val cadence = cadence()
        cadence.record("Attention à la personne, à 2 heures, passez à gauche.", 0L)

        assertTrue(cadence.blocked("Attention à la personne, à 3 heures, passez à gauche.", 1_000L))
        assertTrue(cadence.blocked("Attention à la personne, à 2 heures, passez à gauche.", 5_000L))
    }

    @Test
    fun `deux annonces differentes espacees par le silence`() {
        val cadence = cadence()
        cadence.record(BARRIERE, 0L)

        assertTrue(cadence.blocked(PERSONNE, 1_000L))
        assertFalse(cadence.blocked(PERSONNE, 6_000L))
    }

    @Test
    fun `urgence passe le silence, deux urgences gardent deux secondes`() {
        val cadence = cadence()
        cadence.record(BARRIERE, 0L)

        assertFalse(cadence.blocked(PERSONNE, 500L, urgent = true))
        cadence.record(PERSONNE, 500L, urgent = true)
        assertTrue(cadence.blocked(CYCLE, 1_000L, urgent = true))
        assertFalse(cadence.blocked(CYCLE, 2_500L, urgent = true))
    }

    @Test
    fun `une phrase sans attention ne consomme pas le silence`() {
        val cadence = cadence()
        cadence.record(VOITURE, 0L)

        assertFalse(cadence.blocked(BARRIERE, 100L))
    }

    @Test
    fun `reset oublie les annonces`() {
        val cadence = cadence()
        cadence.record(BARRIERE, 0L)
        cadence.reset()

        assertFalse(cadence.blocked(BARRIERE, 100L))
    }

    private companion object {
        const val BARRIERE = "Attention à la barrière, à 2 heures."
        const val PERSONNE = "Attention à la personne, à 2 heures, passez à gauche."
        const val CYCLE = "Attention, vélo en mouvement, à 9 heures."
        const val VOITURE = "Voiture en mouvement, à 5 mètres."
    }
}
