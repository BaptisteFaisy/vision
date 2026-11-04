package dev.sam3vision.app.isee

import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `configure()` idempotent : une reconfiguration à l'identique (resync
 * réglages, rotation, retour premier plan) ne referme ni ne rouvre la
 * session — l'état affiché (lien, panne, erreur) ne saute plus. Seule une
 * vraie reconfiguration (endpoint, prompts, mode) recrée la session, et
 * remet alors les compteurs du lien à zéro avec elle.
 */
class LiveProbeConfigureTest {

    @Test
    fun `configure répété à l'identique garde la session`() = runTest {
        val (probe, fake) = probeHarness(this)
        val session = probe.session
        probe.configure(PROBE_TEST_ENDPOINT)
        assertSame(session, probe.session)
        assertEquals(0, fake.closed)
    }

    @Test
    fun `traversée stable, setCrossingActive répété ne referme rien`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.setCrossingActive(Settings.Default, true)
        assertEquals(1, fake.closed)
        val session = probe.session
        probe.setCrossingActive(Settings.Default, true)
        assertSame(session, probe.session)
        assertEquals(1, fake.closed)
    }

    @Test
    fun `vraie bascule traversée, compteurs du lien remis à zéro`() = runTest {
        val (probe, _) = probeHarness(this)
        repeat(3) { probe.link.onFailure() }
        assertEquals(3, probe.link.consecutiveFailures)
        probe.setCrossingActive(Settings.Default, true)
        assertEquals(0, probe.link.consecutiveFailures)
        assertEquals(0, probe.link.reconnects)
    }

    @Test
    fun `endpoint changé, la session est recréée`() = runTest {
        val (probe, fake) = probeHarness(this)
        val session = probe.session
        probe.configure("https://autre.test")
        assertNotSame(session, probe.session)
        assertEquals(1, fake.closed)
    }
}
