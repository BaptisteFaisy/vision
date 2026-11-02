package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vote temporel du feu piéton : accord **glissant** pour le vert, sortie
 * immédiate sur rouge prouvé. Les lectures sont fabriquées (deux entrées :
 * couleur de la frame et rouge prouvé) : ce test verrouille la **règle**,
 * pas la couleur — la couleur reste le domaine de `PedLightGateTest`.
 *
 * Chiffres de référence (banc `feux-improve/a5`, 980 séquences FR, voir
 * `docs/MESURES-FEU-TEMPOREL.md`) : 371/400 verts trouvés, 16 faux verts
 * (contre 107 par frame), latence verte p50 180 ms, soit +123 ms p50 pour
 * −85 % de faux verts.
 */
class LightTemporalVoteTest {

    private fun green() = CrossingState.Green

    private fun doubt() = CrossingState.Red

    /** Joue [frames] (couleur, rouge prouvé) et rend la suite des verdicts. */
    private fun play(
        vote: LightTemporalVote,
        vararg frames: Pair<CrossingState, Boolean>,
    ): List<CrossingState> = frames.map { (state, red) -> vote.onFrame(state, red) }

    @Test
    fun `latence en frames - le vert ne sort jamais avant trois preuves`() {
        val vote = LightTemporalVote()
        assertEquals(CrossingState.Red, vote.onFrame(green(), false))
        assertEquals(CrossingState.Red, vote.onFrame(green(), false))
        assertEquals(CrossingState.Green, vote.onFrame(green(), false))
    }

    @Test
    fun `trois verts sur cinq suffisent, ou qu'ils soient dans la fenetre`() {
        // 3 vertes non consécutives parmi 5 : positions 1, 3, 5.
        val vote = LightTemporalVote()
        val verdicts = play(
            vote,
            green() to false,
            doubt() to false,
            green() to false,
            doubt() to false,
            green() to false,
        )
        assertEquals(CrossingState.Green, verdicts.last())
        // Sortie : 2 non-vertes ne suffisent pas à retirer le vert, la 3e si.
        assertEquals(CrossingState.Green, vote.onFrame(doubt(), false))
        assertEquals(CrossingState.Red, vote.onFrame(doubt(), false))
    }

    @Test
    fun `deux verts sur cinq ne prouvent rien - on attend`() {
        val vote = LightTemporalVote()
        val verdicts = play(
            vote,
            green() to false,
            doubt() to false,
            green() to false,
            doubt() to false,
            doubt() to false,
        )
        assertTrue("aucun vert attendu sur 2/5 : $verdicts", verdicts.none { it == CrossingState.Green })
    }

    @Test
    fun `un intrus isole ne casse pas un vert etabli`() {
        val vote = LightTemporalVote()
        repeat(3) { vote.onFrame(green(), false) }
        assertEquals(CrossingState.Green, vote.onFrame(doubt(), false))
        assertEquals(CrossingState.Green, vote.onFrame(green(), false))
        // Deux doutes consécutifs, eux, retirent le vert (anti-clignotement).
        vote.onFrame(doubt(), false)
        assertEquals(CrossingState.Red, vote.onFrame(doubt(), false))
    }

    @Test
    fun `un intrus isole au milieu d un vert ne retarde pas l entree`() {
        // Le glissement est ce qui distingue ce vote de l'accord consécutif :
        // 2 vertes, 1 doute, puis 1 verte = 3 preuves sur les 5 dernières.
        val vote = LightTemporalVote()
        play(vote, green() to false, green() to false, doubt() to false)
        assertEquals(CrossingState.Green, vote.onFrame(green(), false))
    }

    @Test
    fun `la fenetre glisse - les vieilles preuves sortent`() {
        val vote = LightTemporalVote()
        play(vote, green() to false, green() to false)
        repeat(3) { vote.onFrame(doubt(), false) }
        play(vote, green() to false, green() to false)
        // 4 vertes sur 7 frames, mais 2 seulement dans la fenêtre des 5 dernières.
        assertEquals(CrossingState.Red, vote.state)
        assertEquals(CrossingState.Green, vote.onFrame(green(), false))
    }

    @Test
    fun `rouge prouve court-circuite - sortie immediate, sans deuxieme frame`() {
        val vote = LightTemporalVote()
        repeat(3) { vote.onFrame(green(), false) }
        assertEquals(CrossingState.Green, vote.state)
        assertEquals(CrossingState.Red, vote.onFrame(doubt(), true))
    }

    @Test
    fun `un rouge prouve efface la preuve verte - pas de re-entree sur les frames d avant`() {
        val vote = LightTemporalVote()
        repeat(3) { vote.onFrame(green(), false) }
        vote.onFrame(doubt(), true)
        assertEquals(CrossingState.Red, vote.onFrame(green(), false))
        assertEquals(CrossingState.Red, vote.onFrame(green(), false))
        assertEquals(CrossingState.Green, vote.onFrame(green(), false))
    }

    @Test
    fun `un vert melange de rouge franc ne prouve pas le vert`() {
        val vote = LightTemporalVote()
        repeat(6) { vote.onFrame(green(), true) }
        assertEquals(CrossingState.Red, vote.state)
    }

    @Test
    fun `clignotant rouge-vert en alternance rapide - jamais vert`() {
        // Le danger : un feu qui alterne vert et rouge franc (feu voisin,
        // clignotant, bascule instrumentée). Sans le court-circuit du rouge
        // prouvé, 3 vertes sur 5 frames suffiraient dès la 5e frame.
        val vote = LightTemporalVote()
        repeat(6) {
            assertEquals(CrossingState.Red, vote.onFrame(green(), false))
            assertEquals(CrossingState.Red, vote.onFrame(doubt(), true))
        }
    }

    @Test
    fun `clignotant du vert - un vert qui clignote reste un vert`() {
        // Cas assumé et distinct : le bonhomme vert clignote (3 s de fin de
        // traversée). Les frames éteintes ne prouvent pas le rouge, elles
        // n'empêchent donc pas l'accord 3/5 — traverser reste licite.
        val vote = LightTemporalVote()
        assertEquals(
            CrossingState.Green,
            play(
                vote,
                green() to false,
                doubt() to false,
                green() to false,
                doubt() to false,
                green() to false,
            ).last(),
        )
    }

    @Test
    fun `un feu qui ne montre que du rouge ne passe jamais vert`() {
        val vote = LightTemporalVote()
        repeat(12) {
            assertEquals(CrossingState.Red, vote.onFrame(doubt(), it % 2 == 0))
        }
    }

    @Test
    fun `reset repart de zero et rend un feu muet`() {
        val vote = LightTemporalVote()
        repeat(3) { vote.onFrame(green(), false) }
        assertEquals(CrossingState.Green, vote.state)
        vote.reset()
        assertEquals(CrossingState.Red, vote.state)
        assertEquals(CrossingState.Red, vote.onFrame(green(), false))
        assertEquals(CrossingState.Red, vote.onFrame(green(), false))
        assertEquals(CrossingState.Green, vote.onFrame(green(), false))
    }

    @Test
    fun `determinisme - memes frames, memes verdicts, deux votes independants`() {
        val frames = List(40) { index ->
            val vert = (index * 7 + index / 3) % 5 != 0
            Pair(if (vert) CrossingState.Green else CrossingState.Red, index % 11 == 0)
        }
        val first = LightTemporalVote()
        val second = LightTemporalVote()
        assertEquals(
            frames.map { (state, red) -> first.onFrame(state, red) },
            frames.map { (state, red) -> second.onFrame(state, red) },
        )
    }

    @Test
    fun `invariant - jamais d entree en vert sans trois preuves dans la fenetre`() {
        val random = java.util.Random(42)
        val vote = LightTemporalVote()
        val history = mutableListOf<Boolean>()
        var previous = CrossingState.Red
        var misses = 0
        repeat(500) { index ->
            val provesGreen = random.nextDouble() < 0.55
            val provesRed = !provesGreen && random.nextDouble() < 0.10
            history += provesGreen
            val state = vote.onFrame(
                if (provesGreen) CrossingState.Green else CrossingState.Red,
                provesRed,
            )
            if (previous == CrossingState.Red && state == CrossingState.Green) {
                assertTrue(
                    "entrée en vert sans ${LightTemporalVote.NEED_GREEN_FRAMES} preuves " +
                        "dans la fenêtre (frame $index)",
                    history.takeLast(LightTemporalVote.WINDOW_FRAMES)
                        .count { it } >= LightTemporalVote.NEED_GREEN_FRAMES,
                )
            }
            misses = if (provesGreen) 0 else misses + 1
            if (state == CrossingState.Green) {
                assertTrue(
                    "vert tenu au-delà de la sortie (frame $index)",
                    misses < LightTemporalVote.EXIT_FRAMES,
                )
            }
            previous = state
        }
    }

    @Test
    fun `parametres incoherents refuses a la construction`() {
        assertThrows(IllegalArgumentException::class.java) { LightTemporalVote(window = 0) }
        assertThrows(IllegalArgumentException::class.java) { LightTemporalVote(window = 3, needGreen = 4) }
        assertThrows(IllegalArgumentException::class.java) { LightTemporalVote(exitFrames = 0) }
    }

    @Test
    fun `le seuil de rouge prouve est la constante de la porte`() {
        assertTrue(LightTemporalVote.redProven(PedLightGate.RED_MAX_FRACTION))
        assertTrue(LightTemporalVote.redProven(PedLightGate.RED_MAX_FRACTION + 0.01f))
        assertFalse(LightTemporalVote.redProven(PedLightGate.RED_MAX_FRACTION - 0.001f))
        assertFalse(LightTemporalVote.redProven(0f))
    }
}
