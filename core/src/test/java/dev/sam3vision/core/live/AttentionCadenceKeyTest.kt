package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadence « Attention » branchée sur la table centrale : avec une clé
 * [AntiRepeat], la redite suit la durée de la table (2 s pour un poteau, pas
 * les 20 s historiques) ; sans clé, le comportement historique est inchangé
 * (couvert par `AttentionCadenceTest`, intouché).
 */
class AttentionCadenceKeyTest {

    @Test
    fun `cle poteau = redite 2 s au lieu de 20 s`() {
        val cadence = AttentionCadence(gapMs = 0L)
        val key = AntiRepeat.KEY_POLE
        cadence.record(BARRIERE, 0L, key = key)

        assertTrue(cadence.blocked(BARRIERE, 1_000L, key = key))
        assertFalse(cadence.blocked(BARRIERE, 2_000L, key = key))
    }

    @Test
    fun `sans cle = historique inchange`() {
        val cadence = AttentionCadence(gapMs = 0L)
        cadence.record(BARRIERE, 0L)

        assertTrue(cadence.blocked(BARRIERE, 2_000L))
        assertFalse(cadence.blocked(BARRIERE, 20_000L))
    }

    @Test
    fun `deux cles = deux redites independantes`() {
        val cadence = AttentionCadence(gapMs = 0L)
        cadence.record(BARRIERE, 0L, key = AntiRepeat.KEY_POLE)
        cadence.record(PERSONNE, 0L, key = AntiRepeat.KEY_HUMAN_SOFT)

        assertFalse(cadence.blocked(BARRIERE, 2_000L, key = AntiRepeat.KEY_POLE))
        assertFalse(cadence.blocked(PERSONNE, 2_000L, key = AntiRepeat.KEY_HUMAN_SOFT))
        assertTrue(cadence.blocked(BARRIERE, 1_000L, key = AntiRepeat.KEY_POLE))
    }

    @Test
    fun `cle sans delai = pas de redite, silence seul`() {
        val cadence = AttentionCadence(gapMs = 0L)
        cadence.record(PERSONNE, 0L, key = AntiRepeat.KEY_HUMAN_IMMINENT)

        assertFalse(cadence.blocked(PERSONNE, 100L, key = AntiRepeat.KEY_HUMAN_IMMINENT))
    }

    @Test
    fun `cle inconnue = pas de redite, silence seul`() {
        val cadence = AttentionCadence(gapMs = 0L)
        cadence.record(BARRIERE, 0L, key = "n-importe-quoi")

        assertFalse(cadence.blocked(BARRIERE, 100L, key = "n-importe-quoi"))
    }

    @Test
    fun `silence entre annonces garde avec cle`() {
        val cadence = AttentionCadence(gapMs = 6_000L)
        cadence.record(BARRIERE, 0L, key = AntiRepeat.KEY_POLE)

        assertTrue("autre annonce trop proche : tue par le silence", cadence.blocked(PERSONNE, 1_000L, key = AntiRepeat.KEY_HUMAN_SOFT))
        assertFalse(cadence.blocked(PERSONNE, 6_000L, key = AntiRepeat.KEY_HUMAN_SOFT))
    }

    private companion object {
        const val BARRIERE = "Attention à la barrière, à 2 heures."
        const val PERSONNE = "Attention à la personne, à 2 heures, passez à gauche."
    }
}
