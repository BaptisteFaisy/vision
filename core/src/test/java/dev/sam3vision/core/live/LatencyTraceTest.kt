package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chronologie µs d'une frame : chaque étage est l'écart entre deux marques, la latence
 * perçue est le cycle complet capteur → premier pixel, et l'A/R n'est que l'étage `http`.
 */
class LatencyTraceTest {

    private val trace = LatencyTrace()

    /** Une frame complète, aux chiffres du banc : somme des sept étages = 684,8 ms. */
    private fun frameComplete() {
        trace.start(0L)
        trace.mark(LatencyStage.CAPTURE, 24_500_000L)
        trace.mark(LatencyStage.ENCODE, 33_300_000L)
        trace.mark(LatencyStage.QUEUE, 218_000_000L)
        trace.mark(LatencyStage.HTTP, 644_200_000L)
        trace.mark(LatencyStage.DECODE, 665_700_000L)
        trace.mark(LatencyStage.COMPOSE, 667_400_000L)
        trace.mark(LatencyStage.DISPLAY, 684_800_000L)
    }

    @Test
    fun `trace vide ne mesure rien`() {
        val frame = trace.snapshot()
        LatencyStage.entries.forEach { assertEquals(-1L, frame.stageUs(it)) }
        assertEquals(-1L, frame.perceivedUs)
        assertEquals(-1L, frame.measuredUs)
        assertEquals(-1L, frame.rttUs)
        assertEquals(-1L, frame.networkUs)
        assertFalse(frame.complete)
        assertFalse(frame.known)
        assertTrue(frame.isEmpty)
    }

    @Test
    fun `sept marques donnent sept etages et la latence percue`() {
        frameComplete()
        val frame = trace.snapshot()
        assertEquals(24_500L, frame.stageUs(LatencyStage.CAPTURE))
        assertEquals(8_800L, frame.stageUs(LatencyStage.ENCODE))
        assertEquals(184_700L, frame.stageUs(LatencyStage.QUEUE))
        assertEquals(426_200L, frame.stageUs(LatencyStage.HTTP))
        assertEquals(21_500L, frame.stageUs(LatencyStage.DECODE))
        assertEquals(1_700L, frame.stageUs(LatencyStage.COMPOSE))
        assertEquals(17_400L, frame.stageUs(LatencyStage.DISPLAY))
        assertEquals(684_800L, frame.perceivedUs)
        assertEquals(684_800L, frame.measuredUs)
        assertTrue(frame.complete)
        assertTrue(frame.known)
    }

    @Test
    fun `l aller-retour n est que l etage http`() {
        frameComplete()
        val frame = trace.snapshot(serverUs = 300_000L)
        assertEquals(426_200L, frame.rttUs)
        assertEquals(126_200L, frame.networkUs)
    }

    @Test
    fun `sans duree serveur la part reseau n est pas inventee`() {
        frameComplete()
        val frame = trace.snapshot()
        assertEquals(426_200L, frame.rttUs)
        assertEquals(-1L, frame.networkUs)
    }

    @Test
    fun `les instants sont arrondis a la microseconde la plus proche`() {
        trace.start(0L)
        trace.mark(LatencyStage.CAPTURE, 1_500L) // 1,5 µs → 2
        trace.mark(LatencyStage.ENCODE, 3_999L) // 2,499 µs → 2
        val frame = trace.snapshot()
        assertEquals(2L, frame.stageUs(LatencyStage.CAPTURE))
        assertEquals(2L, frame.stageUs(LatencyStage.ENCODE))
    }

    @Test
    fun `un etage manquant casse le cycle mais garde ceux du debut`() {
        trace.start(0L)
        trace.mark(LatencyStage.CAPTURE, 10_000L)
        trace.mark(LatencyStage.ENCODE, 20_000L)
        trace.mark(LatencyStage.QUEUE, 30_000L)
        trace.mark(LatencyStage.HTTP, 40_000L)
        trace.mark(LatencyStage.DECODE, 50_000L)
        trace.mark(LatencyStage.COMPOSE, 60_000L)
        // DISPLAY jamais posé : la frame n'a pas atteint le premier pixel.
        val frame = trace.snapshot()
        assertEquals(10L, frame.stageUs(LatencyStage.CAPTURE))
        assertEquals(10L, frame.stageUs(LatencyStage.COMPOSE))
        assertEquals(-1L, frame.stageUs(LatencyStage.DISPLAY))
        assertFalse(frame.complete)
        assertEquals(-1L, frame.perceivedUs)
        assertEquals(60L, frame.measuredUs) // somme partielle, jamais zéro inventé
    }

    @Test
    fun `une marque dans le desordre ne donne pas d etage`() {
        trace.start(0L)
        trace.mark(LatencyStage.CAPTURE, 10_000L)
        trace.mark(LatencyStage.ENCODE, 5_000L) // avant la capture : impossible
        val frame = trace.snapshot()
        assertEquals(10L, frame.stageUs(LatencyStage.CAPTURE))
        assertEquals(-1L, frame.stageUs(LatencyStage.ENCODE))
    }

    @Test
    fun `sans start le premier etage manque mais l encodage se mesure`() {
        trace.mark(LatencyStage.CAPTURE, 10_000L)
        trace.mark(LatencyStage.ENCODE, 15_000L)
        val frame = trace.snapshot()
        assertEquals(-1L, frame.stageUs(LatencyStage.CAPTURE)) // pas de point de départ
        assertEquals(5L, frame.stageUs(LatencyStage.ENCODE))
    }

    @Test
    fun `reset efface toutes les marques`() {
        frameComplete()
        trace.reset()
        assertTrue(trace.snapshot().isEmpty)
    }

    @Test
    fun `l horloge par defaut est utilisee quand aucun instant n est donne`() {
        var now = 1_000_000L
        val clocked = LatencyTrace { now }
        clocked.start()
        now += 2_000_000L
        clocked.mark(LatencyStage.CAPTURE)
        assertEquals(2_000L, clocked.snapshot().stageUs(LatencyStage.CAPTURE))
    }

    @Test
    fun `of copie le tableau fourni`() {
        val stages = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        val frame = LatencyFrame.of(stages, serverUs = 1_000L)
        stages[0] = 999L
        assertEquals(1L, frame.stageUs(LatencyStage.CAPTURE))
        assertEquals(28L, frame.perceivedUs)
        assertEquals(1_000L, frame.serverUs)
    }

    @Test
    fun `of complete les etages manquants avec moins un`() {
        val frame = LatencyFrame.of(longArrayOf(1L, 2L))
        assertEquals(1L, frame.stageUs(LatencyStage.CAPTURE))
        assertEquals(2L, frame.stageUs(LatencyStage.ENCODE))
        assertEquals(-1L, frame.stageUs(LatencyStage.QUEUE))
        assertEquals(-1L, frame.perceivedUs)
        assertFalse(frame.complete)
    }
}