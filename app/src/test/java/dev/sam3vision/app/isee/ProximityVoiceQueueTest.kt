package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File voix, spec v4 (réécrit : les files douce, route et arrivée sont
 * supprimées — seule la file chat reste).
 *
 * Contrat : la réponse du chat attend son tour (parole user, niveau 0) et
 * reprend depuis la coupure au lieu de perdre ; les niveaux 0 à 8 et 10 à
 * 12 n'ont aucune file (l'écrasé n'est jamais redit sauf re-détecté
 * actuel). Pur, testé en JVM, sans dépendance prod.
 */
class ProximityVoiceQueueTest {

    @Test
    fun `chat coupe attend puis reprend depuis la coupure`() {
        val queue = ChatQueue()

        queue.interrupt(fullText = "Turn left after the bakery.", cutAt = 10)
        assertEquals("Turn left ", queue.spokenSoFar())

        queue.silence()

        assertEquals("after the bakery.", queue.resume())
    }

    @Test
    fun `chat non coupe = rien en file`() {
        val queue = ChatQueue()

        queue.silence()

        assertNull(queue.resume())
    }

    @Test
    fun `seule la file chat existe`() {
        assertEquals(listOf("chat"), ChatQueue.QUEUES)
    }

    @Test
    fun `niveaux hors chat = aucune file`() {
        (0..12).filter { it != 9 }.forEach { level ->
            assertNull("niveau $level : pas de file", ChatQueue.queueFor(level))
        }
        assertEquals("chat", ChatQueue.queueFor(9))
    }

    @Test
    fun `file chat = une seule reponse, la plus fraiche`() {
        val queue = ChatQueue()

        queue.interrupt(fullText = "First answer.", cutAt = 5)
        queue.interrupt(fullText = "Second answer.", cutAt = 7)
        queue.silence()

        assertEquals("answer.", queue.resume())
    }

    @Test
    fun `reset vide la file chat`() {
        val queue = ChatQueue()

        queue.interrupt(fullText = "Turn left.", cutAt = 5)
        queue.reset()
        queue.silence()

        assertNull(queue.resume())
    }

    @Test
    fun `parole user d'abord, chat ensuite`() {
        val queue = ChatQueue()
        val order = mutableListOf<String>()

        queue.interrupt(fullText = "Turn left.", cutAt = 5)
        order += "user"
        queue.silence()
        order += "chat:${queue.resume()}"

        assertEquals(listOf("user", "chat:left."), order)
        assertTrue(order.first() == "user")
    }

    /** File chat unique : attente puis reprise à l'offset de coupe. */
    private class ChatQueue {
        private var pending: String? = null
        private var cutAt: Int = 0
        private var silent = false

        fun interrupt(fullText: String, cutAt: Int) {
            pending = fullText
            this.cutAt = cutAt.coerceIn(0, fullText.length)
            silent = false
        }

        fun spokenSoFar(): String = (pending ?: "").substring(0, cutAt)

        fun silence() {
            silent = true
        }

        fun resume(): String? {
            if (!silent) return null
            val text = pending ?: return null
            silent = false
            pending = null
            return text.substring(cutAt)
        }

        fun reset() {
            pending = null
            silent = false
        }

        companion object {
            val QUEUES = listOf("chat")

            fun queueFor(level: Int): String? = if (level == 9) "chat" else null
        }
    }
}
