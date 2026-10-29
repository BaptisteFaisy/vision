package dev.sam3vision.core.danger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Affectation Hongroise (JUnit seul) : optimum, filtre au seuil, rectangles, vide. */
class HungarianTest {

    @Test
    fun `le cout minimal global prime sur le glouton`() {
        // Glouton (ligne 0 → col 0 à 0,1) coûterait 0,1 + 0,9 ; l'optimum est 0,2 + 0,3.
        val cost = arrayOf(doubleArrayOf(0.1, 0.2), doubleArrayOf(0.3, 0.9))
        val found = Hungarian.assign(cost, 1.0)
        assertEquals(listOf(0 to 1, 1 to 0), found.matches)
        assertTrue(found.unmatchedRows.isEmpty())
        assertTrue(found.unmatchedCols.isEmpty())
    }

    @Test
    fun `les paires au-dela du seuil sont rendues aux non-apparies`() {
        val cost = arrayOf(doubleArrayOf(0.1, 0.9), doubleArrayOf(0.9, 0.95))
        val found = Hungarian.assign(cost, 0.5)
        assertEquals(listOf(0 to 0), found.matches)
        assertEquals(listOf(1), found.unmatchedRows)
        assertEquals(listOf(1), found.unmatchedCols)
    }

    @Test
    fun `plus de colonnes que de lignes apparie chaque ligne au mieux`() {
        val cost = arrayOf(doubleArrayOf(0.5, 0.1, 0.6))
        val found = Hungarian.assign(cost, 1.0)
        assertEquals(listOf(0 to 1), found.matches)
        assertEquals(listOf(0, 2), found.unmatchedCols)
    }

    @Test
    fun `matrice vide ne plante pas`() {
        assertEquals(0, Hungarian.assign(arrayOf(), 1.0).matches.size)
        assertEquals(2, Hungarian.assign(arrayOf(doubleArrayOf(), doubleArrayOf()), 1.0).unmatchedRows.size)
    }
}
