package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foule vue par la sonde (audit foule du 01/11, P0 point 1) : un corps dans la **dernière
 * réponse** suffit à basculer la cadence sur `Cadence.Crowd`, et on n'en sort qu'après 3 s
 * sans corps. Sans cette hystérésis l'état clignoterait au rythme des frames (1,4-1,7
 * décisions/s) et le second vol se refermerait au moment précis du contact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveProbeCrowdTest {

    @Test
    fun `l hysteresis entre au premier corps et sort apres trois secondes`() {
        assertFalse(
            "aucun corps, jamais vu : hors foule",
            crowdOf(previous = false, bodies = 0, sinceMs = 0L, nowMs = 5_000L),
        )
        assertTrue(
            "un seul corps suffit pour entrer",
            crowdOf(previous = false, bodies = 1, sinceMs = 2_000L, nowMs = 2_000L),
        )
        assertTrue(
            "2,9 s sans corps : encore en foule",
            crowdOf(previous = true, bodies = 0, sinceMs = 2_000L, nowMs = 4_900L),
        )
        assertFalse(
            "3,1 s sans corps : sorti de foule",
            crowdOf(previous = true, bodies = 0, sinceMs = 2_000L, nowMs = 5_100L),
        )
    }

    @Test
    fun `un corps bascule la sonde en foule et trois secondes sans corps l en sort`() = runTest {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = this,
            clock = { ns },
            background = StandardTestDispatcher(testScheduler),
            clientFor = { _, _, _ -> fake },
        )
        // Scheduler coupé : ce test verrouille la cadence, pas les keyframes.
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        probe.configure(PROBE_TEST_ENDPOINT)
        probe.begin()
        advanceUntilIdle()
        assertFalse("aucune réponse encore : hors foule", probe.crowd)
        val session = probe.session ?: error("session chaude attendue")

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(bodies = 1))
        advanceUntilIdle()
        assertTrue("un corps dans la dernière réponse : foule", probe.crowd)
        assertEquals("le budget de 600 ms n'a pas encore refermé", 2, session.slots)

        session.stats.reset()
        (1..3).forEach { _ -> session.stats.record(300L) }
        session.regulate()
        assertEquals("300 ms en foule : le double vol reste", 2, session.slots)

        at(2_900)
        probe.onFrame("B", captureAtNs = 3_900_000_000L)
        advanceUntilIdle()
        fake.respond("B", frame(bodies = 0))
        advanceUntilIdle()
        assertTrue("2,9 s sans corps : encore en foule", probe.crowd)

        at(3_100)
        probe.onFrame("C", captureAtNs = 4_100_000_000L)
        advanceUntilIdle()
        fake.respond("C", frame(bodies = 0))
        advanceUntilIdle()
        assertFalse("3,1 s sans corps : sorti de foule", probe.crowd)

        session.stats.reset()
        (1..3).forEach { _ -> session.stats.record(300L) }
        session.regulate()
        assertEquals("de retour en cadence rapide, 300 ms referment", 1, session.slots)
    }

    /** Horloge monotone pilotée (ns), même base qu'`imageInfo.timestamp`. */
    private var ns = 0L

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame avec [bodies] corps (ou aucun) : ni masque ni Android à moquer. */
    private fun frame(bodies: Int) = LiveFrame(
        width = 512,
        height = 1138,
        elapsedMs = 100L,
        objects = List(bodies) { person() },
    )

    /** Personne détectée, au centre, sans masque. */
    private fun person() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(206f, 400f, 306f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )
}
