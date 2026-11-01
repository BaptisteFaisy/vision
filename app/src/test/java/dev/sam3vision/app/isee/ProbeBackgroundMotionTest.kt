package dev.sam3vision.app.isee

import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.traverse.BackgroundFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * La pompe du fond V1 : dépôt latest-only hors fil d'analyse, estimation
 * séquentielle au vrai estimateur, reset jeteur — décodeur et coureur faux
 * (le vrai décodage est Android, le vrai coureur un fil démon).
 */
class ProbeBackgroundMotionTest {

    private val decoded = mutableListOf<String>()
    private val queued = ArrayDeque<Runnable>()
    private lateinit var frames: Map<String, BackgroundFrame?>
    private var savedDecoder: (String) -> BackgroundFrame? = { null }
    private var savedRunner: (Runnable) -> Unit = { it.run() }

    @Before
    fun brancherFaux() {
        savedDecoder = ProbeBackgroundMotion.decoder
        savedRunner = ProbeBackgroundMotion.drainRunner
        decoded.clear()
        queued.clear()
        frames = mapOf(
            "a" to lumaFrame(),
            "b" to lumaFrame(),
            "c" to lumaFrame(),
            "etroite" to lumaFrame(width = 48, height = 48),
        )
        ProbeBackgroundMotion.decoder = { key -> decoded.add(key); frames[key] }
        ProbeBackgroundMotion.drainRunner = { queued.add(it) }
        ProbeBackgroundMotion.reset()
    }

    @After
    fun rebrancherDefauts() {
        ProbeBackgroundMotion.decoder = savedDecoder
        ProbeBackgroundMotion.drainRunner = savedRunner
        ProbeBackgroundMotion.reset()
    }

    @Test
    fun `premiere frame sans avis, decodee une fois`() {
        ProbeBackgroundMotion.offer("a", 100L)
        assertEquals(1, queued.size)
        runQueued()
        assertEquals(listOf("a"), decoded)
        assertNull(ProbeBackgroundMotion.lastMotion)
    }

    @Test
    fun `paire ordonnee publie dt et dimensions`() {
        ProbeBackgroundMotion.offer("a", 100L)
        runQueued()
        ProbeBackgroundMotion.offer("b", 200L)
        runQueued()
        val motion = ProbeBackgroundMotion.lastMotion
        assertNotNull(motion)
        assertEquals(MotionSample.Zero, motion!!.global)
        assertEquals(64, motion.lumaWidth)
        assertEquals(64, motion.lumaHeight)
        assertEquals(100L, motion.dtMs)
        assertEquals(listOf("a", "b"), decoded)
    }

    @Test
    fun `rafale - rien de synchrone, seule la derniere decodee`() {
        ProbeBackgroundMotion.offer("a", 100L)
        ProbeBackgroundMotion.offer("b", 200L)
        ProbeBackgroundMotion.offer("c", 300L)
        assertEquals(1, queued.size)
        assertEquals(emptyList<String>(), decoded)
        runQueued()
        assertEquals(listOf("c"), decoded)
        assertNull(ProbeBackgroundMotion.lastMotion)
    }

    @Test
    fun `reset jette le depot sans decodage`() {
        ProbeBackgroundMotion.offer("a", 100L)
        ProbeBackgroundMotion.reset()
        runQueued()
        assertEquals(emptyList<String>(), decoded)
        assertNull(ProbeBackgroundMotion.lastMotion)
    }

    @Test
    fun `reset pendant le decodage jette le resultat`() {
        ProbeBackgroundMotion.offer("a", 100L)
        runQueued()
        ProbeBackgroundMotion.decoder = { key ->
            decoded.add(key)
            if (key == "b") ProbeBackgroundMotion.reset()
            frames[key]
        }
        ProbeBackgroundMotion.offer("b", 200L)
        runQueued()
        assertEquals(listOf("a", "b"), decoded)
        assertNull(ProbeBackgroundMotion.lastMotion)
    }

    @Test
    fun `dimensions changees sans avis puis reprise`() {
        ProbeBackgroundMotion.offer("a", 100L)
        runQueued()
        ProbeBackgroundMotion.offer("etroite", 200L)
        runQueued()
        assertNull(ProbeBackgroundMotion.lastMotion)
        ProbeBackgroundMotion.offer("etroite", 300L)
        runQueued()
        val motion = ProbeBackgroundMotion.lastMotion
        assertNotNull(motion)
        assertEquals(100L, motion!!.dtMs)
    }

    @Test
    fun `frame illisible efface prev, sans avis`() {
        ProbeBackgroundMotion.offer("a", 100L)
        runQueued()
        ProbeBackgroundMotion.offer("inconnue", 200L)
        runQueued()
        assertNull(ProbeBackgroundMotion.lastMotion)
        ProbeBackgroundMotion.offer("b", 300L)
        runQueued()
        assertNull(ProbeBackgroundMotion.lastMotion)
        assertEquals(listOf("a", "inconnue", "b"), decoded)
    }

    /** Vide la pompe pilotée (les drains soumis en cascade partent aussi). */
    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    /** Luminance déterministe (même contenu à dimensions égales : paires immobiles). */
    private fun lumaFrame(width: Int = 64, height: Int = 64): BackgroundFrame {
        val luma = ByteArray(width * height) { i -> (i % 251).toByte() }
        return BackgroundFrame(width, height, luma)
    }
}
