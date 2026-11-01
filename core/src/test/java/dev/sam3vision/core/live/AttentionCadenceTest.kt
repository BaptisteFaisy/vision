package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadence des « Attention … » : même annonce jamais deux fois dans la
 * fenêtre (le cadran qui tremble ne fait pas une nouvelle phrase), silence
 * entre deux annonces, urgence qui échappe à la redite mais garde son propre
 * silence, chaque piste à son tour, et les phrases tues par la redite rendues
 * une fois par `pending` — rien d'autre que la famille « Attention » ne
 * consomme le budget.
 */
class AttentionCadenceTest {

    private fun cadence(gapMs: Long = 6_000L) =
        AttentionCadence(gapMs = gapMs, repeatMs = 20_000L, urgentGapMs = 2_000L)

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

    // ── Foules : une clé par piste ────────────────────────────────────────

    @Test
    fun `deux personnes, deux pistes, deux annonces`() {
        val cadence = cadence(gapMs = 0L)
        cadence.record(PERSONNE, 0L, trackId = 1)

        assertFalse(cadence.blocked(PERSONNE, 1_000L, trackId = 2))
        cadence.record(PERSONNE, 1_000L, trackId = 2)
        // La piste 1 garde la sienne : c'est bien une clé par personne.
        assertTrue(cadence.blocked(PERSONNE, 2_000L, trackId = 1))
    }

    @Test
    fun `meme piste bloquee, puis pending la rend a l echeance`() {
        val cadence = cadence(gapMs = 0L)
        cadence.record(PERSONNE, 0L, trackId = 1)

        assertTrue(cadence.blocked(PERSONNE, 5_000L, trackId = 1))
        assertTrue(cadence.pending(19_999L).isEmpty())
        assertEquals(listOf(PERSONNE), cadence.pending(20_000L))
    }

    // ── Urgence : pas de redite ───────────────────────────────────────────

    @Test
    fun `urgence echappe a la redite`() {
        val cadence = cadence()
        cadence.record(PERSONNE, 0L, urgent = true)

        // Son propre silence tient : deux urgences gardent deux secondes.
        assertTrue(cadence.blocked(PERSONNE, 1_000L, urgent = true))
        assertTrue(cadence.pending(30_000L).isEmpty())
        // Passé le silence urgent, la redite ne s'applique pas : elle passe.
        assertFalse(cadence.blocked(PERSONNE, 3_000L, urgent = true))
    }

    @Test
    fun `urgent puis non urgent immediat est bloque`() {
        val cadence = cadence(gapMs = 0L)
        cadence.record(PERSONNE, 1_000L, urgent = true)

        // Silence nul : c'est bien la redite qui la tait, pas le gap.
        assertTrue(cadence.blocked(PERSONNE, 1_100L))
    }

    // ── Annonces en attente ───────────────────────────────────────────────

    @Test
    fun `pending ne rend jamais une phrase deux fois`() {
        val cadence = cadence(gapMs = 0L)
        cadence.record(BARRIERE, 0L)
        assertTrue(cadence.blocked(BARRIERE, 1_000L))
        assertTrue(cadence.blocked(BARRIERE, 2_000L)) // encore tue : une seule entrée

        assertEquals(listOf(BARRIERE), cadence.pending(20_000L))
        assertTrue(cadence.pending(20_500L).isEmpty())
    }

    @Test
    fun `pending perime au dela de trois secondes de retard`() {
        val cadence = cadence(gapMs = 0L)
        cadence.record(BARRIERE, 0L)
        assertTrue(cadence.blocked(BARRIERE, 1_000L))

        assertTrue(cadence.pending(23_500L).isEmpty())
    }

    @Test
    fun `pending rend dans l ordre d arrivee`() {
        val cadence = cadence(gapMs = 0L)
        cadence.record(BARRIERE, 0L)
        cadence.record(CYCLE, 0L)
        assertTrue(cadence.blocked(CYCLE, 500L))
        assertTrue(cadence.blocked(BARRIERE, 600L))

        assertEquals(listOf(CYCLE, BARRIERE), cadence.pending(20_000L))
    }

    @Test
    fun `silence ne met rien en attente`() {
        val cadence = cadence()
        cadence.record(BARRIERE, 0L)

        assertTrue(cadence.blocked(PERSONNE, 1_000L)) // tue par le gap, pas la redite
        assertTrue(cadence.pending(30_000L).isEmpty())
    }

    @Test
    fun `une phrase sans attention n est jamais mise en attente`() {
        val cadence = cadence()
        cadence.record(VOITURE, 0L)

        assertFalse(cadence.blocked(VOITURE, 100L))
        assertTrue(cadence.pending(100_000L).isEmpty())
    }

    @Test
    fun `pending garde huit phrases au plus`() {
        val cadence = cadence(gapMs = 0L)
        FOULE.forEach { cadence.record(it, 0L) }
        FOULE.forEach { assertTrue(cadence.blocked(it, 1_000L)) }

        assertEquals(FOULE.drop(2), cadence.pending(20_000L))
    }

    @Test
    fun `reset vide aussi les annonces en attente`() {
        val cadence = cadence()
        cadence.record(BARRIERE, 0L)
        assertTrue(cadence.blocked(BARRIERE, 1_000L))

        cadence.reset()

        assertTrue(cadence.pending(20_000L).isEmpty())
        assertFalse(cadence.blocked(BARRIERE, 20_000L))
    }

    private companion object {
        const val BARRIERE = "Attention à la barrière, à 2 heures."
        const val PERSONNE = "Attention à la personne, à 2 heures, passez à gauche."
        const val CYCLE = "Attention, vélo en mouvement, à 9 heures."
        const val VOITURE = "Voiture en mouvement, à 5 mètres."

        /** Dix annonces distinctes : dix clés, pour éprouver la borne de la file. */
        val FOULE = listOf(
            "Attention, barrière à droite.",
            "Attention, vélo à gauche.",
            "Attention, poussette devant.",
            "Attention, chien au sol.",
            "Attention, trottoir étroit.",
            "Attention, poteau devant.",
            "Attention, marche descendante.",
            "Attention, porte à droite.",
            "Attention, escalier devant.",
            "Attention, foule dense.",
        )
    }
}
