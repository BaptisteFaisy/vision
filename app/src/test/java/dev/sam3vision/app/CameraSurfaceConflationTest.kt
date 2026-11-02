package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conflation « dernier gagne » des fusions : une seule retenue, un seul
 * travailleur, reprise sans réveil perdu. Pur Kotlin, déterministe.
 */
class ConflatedJobsTest {

    @Test
    fun `offer demarre le premier travailleur seul`() {
        val jobs = ConflatedJobs<String>()

        assertTrue(jobs.offer("a"))
        assertFalse(jobs.offer("b"))
    }

    @Test
    fun `take rend la derniere offerte puis null`() {
        val jobs = ConflatedJobs<String>()
        jobs.offer("a")
        jobs.offer("b")

        assertTrue(jobs.hasPending())
        assertEquals("b", jobs.take())
        assertFalse(jobs.hasPending())
        assertNull(jobs.take())
    }

    @Test
    fun `release sans attente libere, le prochain offer redemarre`() {
        val jobs = ConflatedJobs<String>()
        jobs.offer("a")
        jobs.take()

        assertFalse(jobs.release())
        assertTrue(jobs.offer("b"))
        assertEquals("b", jobs.take())
    }

    @Test
    fun `release avec attente reprend sans reveil perdu`() {
        val jobs = ConflatedJobs<String>()
        jobs.offer("a")
        jobs.take()
        // Offre entre la dernière prise et la libération : l'offer croit un
        // travailleur en vol et n'en démarre pas — release doit reprendre.
        assertFalse(jobs.offer("b"))

        assertTrue(jobs.release())
        assertEquals("b", jobs.take())
        assertFalse(jobs.release())
    }

    @Test
    fun `rafale multithread, une seule valeur retenue, jamais perdue`() {
        val jobs = ConflatedJobs<Int>()
        val threads = (0 until 4).map { shard ->
            Thread {
                repeat(250) { i -> jobs.offer(shard * 1000 + i) }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // Dernier gagne : une valeur (la dernière posée), puis rien.
        assertTrue(jobs.take() != null)
        assertNull(jobs.take())
    }
}
