package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.ReleaseOutcome
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verrou Wi-Fi haute-perf du direct : acquis à la première frame envoyée,
 * rendu par `stop` (entonnoir unique : caméra coupée, fond, `onCleared`),
 * repli silencieux sans radio — jamais de crash, jamais de blocage.
 */
class LiveWifiLockTest {

    private var savedRelease: (suspend (String) -> Result<ReleaseOutcome>)? = null

    @Before
    fun stubRelease() {
        savedRelease = releaseOnStop
        releaseOnStop = { Result.success(ReleaseOutcome(released = false, code = 0, body = "stub")) }
    }

    @After
    fun restoreRelease() {
        releaseOnStop = savedRelease ?: releaseOnStop
    }

    /** Faux verrou radio : compte acquisitions et libérations, pannes pilotées. */
    private class FakeWifiLock(
        var acquireThrows: Boolean = false,
        var releaseThrows: Boolean = false,
    ) : WifiLockHandle {
        var acquires = 0
        var releases = 0

        override fun acquire() {
            if (acquireThrows) error("radio refusée")
            acquires += 1
        }

        override fun release() {
            if (releaseThrows) error("libération refusée")
            releases += 1
        }
    }

    /** Faux planificateur : le test déclenche le timeout à la main. */
    private class FakeScheduler : WifiLockScheduler {
        val delays = mutableListOf<Long>()
        val tasks = mutableListOf<() -> Unit>()
        var cancels = 0

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            delays += delayMs
            tasks += task
            return { cancels += 1 }
        }

        fun fireLast() = tasks.last()()
    }

    private class Harness(val fake: FakeWifiLock = FakeWifiLock()) {
        var now = 1_000L
        val logs = mutableListOf<String>()
        val warns = mutableListOf<String>()
        var factoryThrows = false
        var factoryNull = false
        val scheduler = FakeScheduler()
        val lock = LiveWifiLock(
            context = null,
            factory = WifiLockFactory {
                if (factoryThrows) error("fabrique en panne")
                if (factoryNull) null else fake
            },
            scheduler = scheduler,
            clock = { now },
            log = { logs += it },
            warn = { warns += it },
        )
    }

    @Test
    fun `hold acquiert une fois, les frames suivantes ne re-acquierent pas`() {
        val h = Harness()
        h.lock.hold()
        h.lock.hold()
        h.lock.hold()

        assertTrue(h.lock.isHeld)
        assertEquals(1, h.fake.acquires)
        assertEquals(0, h.fake.releases)
        assertEquals(1, h.logs.count { it.startsWith("wifilock: acquis") })
    }

    @Test
    fun `l_acquisition arme le timeout de securite`() {
        val h = Harness()
        h.lock.hold()

        assertEquals(listOf(WIFI_LOCK_TIMEOUT_MS), h.scheduler.delays)
    }

    @Test
    fun `le timeout passe sans stop force la liberation`() {
        val h = Harness()
        h.lock.hold()
        h.now += WIFI_LOCK_TIMEOUT_MS + 1
        h.scheduler.fireLast()

        assertFalse(h.lock.isHeld)
        assertEquals(1, h.fake.releases)
        assertTrue(h.logs.any { it.startsWith("wifilock: libération forcée") })
    }

    @Test
    fun `un timeout perime ne libere pas un verrou renouvele`() {
        val h = Harness()
        h.lock.hold()
        h.now += WIFI_LOCK_REFRESH_MS + 1
        h.lock.hold()
        h.scheduler.tasks.first()()

        assertTrue(h.lock.isHeld)
        assertEquals(2, h.fake.acquires)
        assertEquals(1, h.fake.releases)
    }

    @Test
    fun `hold passe le delai renouvelle le verrou`() {
        val h = Harness()
        h.lock.hold()
        h.now += WIFI_LOCK_REFRESH_MS + 1
        h.lock.hold()

        assertTrue(h.lock.isHeld)
        assertEquals(2, h.fake.acquires)
        assertEquals(1, h.fake.releases)
        assertTrue(h.logs.any { it == "wifilock: renouvellement" })
    }

    @Test
    fun `release rend la radio, double release sans crash ni double liberation`() {
        val h = Harness()
        h.lock.hold()
        h.lock.release()
        h.lock.release()

        assertFalse(h.lock.isHeld)
        assertEquals(1, h.fake.releases)
        assertEquals(1, h.logs.count { it == "wifilock: relâché" })
    }

    @Test
    fun `release annule le timeout arme`() {
        val h = Harness()
        h.lock.hold()
        h.lock.release()

        assertEquals(1, h.scheduler.cancels)
    }

    @Test
    fun `release sans hold ne touche pas la radio`() {
        val h = Harness()
        h.lock.release()

        assertEquals(0, h.fake.releases)
        assertTrue(h.logs.isEmpty())
    }

    @Test
    fun `fabrique nulle - repli silencieux, le direct continue`() {
        val h = Harness()
        h.factoryNull = true
        h.lock.hold()

        assertFalse(h.lock.isHeld)
        assertEquals(1, h.warns.count { it.startsWith("wifilock: échec") })
    }

    @Test
    fun `fabrique en panne - repli silencieux, jamais de crash`() {
        val h = Harness()
        h.factoryThrows = true
        h.lock.hold()

        assertFalse(h.lock.isHeld)
    }

    @Test
    fun `acquisition refusee - repli silencieux, jamais de crash`() {
        val h = Harness()
        h.fake.acquireThrows = true
        h.lock.hold()

        assertFalse(h.lock.isHeld)
        assertEquals(1, h.warns.count { it.startsWith("wifilock: échec") })
    }

    @Test
    fun `liberation refusee - pas de crash, verrou oublie`() {
        val h = Harness()
        h.lock.hold()
        h.fake.releaseThrows = true
        h.lock.release()

        assertFalse(h.lock.isHeld)
    }

    /** Sonde branchée sur le faux client réseau et le faux verrou radio. */
    private fun wifiProbeHarness(scope: kotlinx.coroutines.test.TestScope): Triple<LiveProbe, FakeWifiLock, ProbeTestClient> {
        val radio = FakeWifiLock()
        val net = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, _ -> net },
            wifiLockFactory = WifiLockFactory { radio },
        )
        probe.configure(PROBE_TEST_ENDPOINT)
        probe.begin()
        return Triple(probe, radio, net)
    }

    @Test
    fun `la 1re frame acquiert, stop relache, double stop sans crash`() = runTest {
        val (probe, radio, net) = wifiProbeHarness(this)

        probe.onFrame("A")
        assertEquals(1, radio.acquires)
        advanceUntilIdle()
        net.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()

        probe.stop()
        assertEquals(1, radio.releases)
        assertFalse(probe.wifiLock.isHeld)

        probe.stop()
        assertEquals(1, radio.releases)
        advanceUntilIdle()
    }

    @Test
    fun `stop sans frame ne touche pas la radio`() = runTest {
        val (probe, radio, _) = wifiProbeHarness(this)

        probe.stop()
        advanceUntilIdle()

        assertEquals(0, radio.acquires)
        assertEquals(0, radio.releases)
    }
}
