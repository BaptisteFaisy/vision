package dev.sam3vision.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Politique d'affichage du spike ([appendLogLine], [shortErrorMessage]) sur JVM :
 * l'écran garde la fin du journal et les erreurs tiennent en une ligne.
 */
class SpikeLogTest {

    @Test
    fun `un journal court est conserve tel quel`() {
        val lines = appendLogLine(listOf("a", "b"), "c")
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `le journal garde les dernieres lignes et jette les plus anciennes`() {
        val lines = (1..SPIKE_LOG_LINES).map { "ligne $it" }
        val grown = appendLogLine(lines, "ligne ${SPIKE_LOG_LINES + 1}")
        assertEquals(SPIKE_LOG_LINES, grown.size)
        assertEquals("ligne 2", grown.first())
        assertEquals("ligne ${SPIKE_LOG_LINES + 1}", grown.last())
    }

    @Test
    fun `une limite nulle vide le journal`() {
        assertEquals(emptyList<String>(), appendLogLine(listOf("a"), "b", maxLines = 0))
        assertEquals(emptyList<String>(), appendLogLine(emptyList(), "b", maxLines = -1))
    }

    @Test
    fun `un message d erreur long est tronque`() {
        val message = "x".repeat(SHORT_ERROR_CHARS + 40)
        val short = shortErrorMessage(RuntimeException(message))
        assertEquals(SHORT_ERROR_CHARS, short.length)
        assertEquals(message.take(SHORT_ERROR_CHARS), short)
    }

    @Test
    fun `un message court est rendu inchange`() {
        assertEquals("flux refusé", shortErrorMessage(RuntimeException("flux refusé")))
    }

    @Test
    fun `un message vide ou absent retombe sur le repli`() {
        assertEquals(FALLBACK_ERROR_MESSAGE, shortErrorMessage(RuntimeException("")))
        assertEquals(FALLBACK_ERROR_MESSAGE, shortErrorMessage(RuntimeException("   ")))
        assertEquals(FALLBACK_ERROR_MESSAGE, shortErrorMessage(RuntimeException()))
    }
}
