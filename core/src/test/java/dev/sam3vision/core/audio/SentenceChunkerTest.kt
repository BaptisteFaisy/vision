package dev.sam3vision.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `un texte court reste un seul morceau`() {
        assertEquals(listOf("Voici la photo d'un chien."), SentenceChunker.chunk("  Voici la photo d'un chien.  "))
        assertTrue(SentenceChunker.chunk("   ").isEmpty())
    }

    @Test
    fun `les phrases sont regroupees sans jamais depasser la limite`() {
        val sentence = "C'est un objet posé sur la table, nettement découpé. "
        val text = sentence.repeat(60).trim()
        val chunks = SentenceChunker.chunk(text, maxChars = 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.none { it.isBlank() })
        // Aucune phrase n'est coupée en deux : chaque morceau finit sur une frontière.
        assertTrue(chunks.all { it.endsWith(".") })
    }

    @Test
    fun `la recolle retrouve tous les mots dans l'ordre`() {
        val text = (1..80).joinToString(". ") { "mot$it" } + "."
        val joined = SentenceChunker.chunk(text, maxChars = 120).joinToString(" ")
        assertEquals(SentenceChunker.chunk(text, maxChars = 100_000).single(), joined)
    }

    @Test
    fun `une phrase demesuree est coupee aux mots`() {
        val text = "alpha ".repeat(400)
        val chunks = SentenceChunker.chunk(text.trim(), maxChars = 300)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 300 })
        assertEquals(400, chunks.sumOf { it.split(" ").size })
    }

    @Test
    fun `les points cardinaux ne coupent pas une abreviation`() {
        val chunks = SentenceChunker.chunk("Il est M. Dupont. Il part.", maxChars = 12)
        assertEquals(listOf("Il est M.", "Dupont.", "Il part."), chunks)
    }
}
