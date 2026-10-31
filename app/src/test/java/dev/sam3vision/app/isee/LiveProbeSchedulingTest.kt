package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le cycle iSee ne doit plus rendre le GPU inactif entre « réponse reçue » et « frame
 * suivante envoyée » : la frame en file part dès que le créneau se libère, la publication
 * (masques, distances, état) vient **après**. La publication détache ses marques, donc
 * le drain ne vole pas les postes de la frame affichée (les vols qui se chevauchent
 * partagent la chronologie en vol : seul le dernier a des marques exactes).
 */
class LiveProbeSchedulingTest {

    /** Horloge monotone pilotée (ns) : même base qu'`imageInfo.timestamp`. */
    private var ns = 0L
    private val clock: () -> Long get() = { ns }

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame sans objet (aucun masque à décorer, aucun Android à moquer) et sa durée serveur. */
    private fun frame(elapsedMs: Long) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = emptyList())

    @Test
    fun `la frame suivante part avant la publication des masques de la precedente`() = runTest {
        val journal = mutableListOf<String>()
        val (probe, fake) = harness(this, journal)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        assertEquals("une frame partie", 1, fake.sent)
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        assertEquals("deuxième créneau : B part aussitôt", 2, fake.sent)
        probe.onFrame("C", captureAtNs = 20_000_000L)
        advanceUntilIdle()
        assertEquals("saturé : C attend en file", 2, fake.sent)
        assertEquals(0, probe.state.value.frames)

        at(40)
        fake.respond("A", frame(111))
        advanceUntilIdle()

        assertEquals(3, fake.sent)
        assertTrue(
            "C doit partir pendant que A n'est pas encore publiée : $journal",
            journal.contains("envoi C (frames=0)"),
        )
        assertEquals("publication de A venue juste après", 1, probe.state.value.frames)

        at(60)
        fake.respond("B", frame(222))
        advanceUntilIdle()
        assertEquals(2, probe.state.value.frames)

        at(80)
        fake.respond("C", frame(333))
        advanceUntilIdle()
        assertEquals(3, probe.state.value.frames)
    }

    @Test
    fun `a deux vols les marques serveur restent par frame, la derniere garde les siennes`() = runTest {
        val journal = mutableListOf<String>()
        val (probe, fake) = harness(this, journal)
        at(10)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        at(15)
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()

        at(20)
        probe.onFrame("C", captureAtNs = 18_000_000L)
        advanceUntilIdle()
        // A répond à 40 ms : la frame C est envoyée avant que A soit publiée.
        at(40)
        fake.respond("A", frame(111))
        advanceUntilIdle()
        val a = probe.state.value.timings
        // A chevauchée par B : seules ses marques serveur survivent (voir ProbeFrame).
        assertEquals(111L, a.serverTotalMs)

        at(60)
        fake.respond("B", frame(222))
        advanceUntilIdle()
        val b = probe.state.value.timings
        // B chevauchée par C : seules ses marques serveur survivent.
        assertEquals(222L, b.serverTotalMs)

        at(80)
        fake.respond("C", frame(333))
        advanceUntilIdle()
        val c = probe.state.value.timings
        assertEquals("C paye son propre aller-retour", 40L, c.sentToReceivedMs)
        assertEquals(2L, c.captureToJpegMs) // exposée à 18, encodée à 20.
        assertEquals(20L, c.jpegToSentMs) // encodée à 20, partie au drain de A (40).
        assertEquals(333L, c.serverTotalMs)
    }

    /** Sonde branchée sur un faux client, sans réseau ni GPU : journal ordonné des envois. */
    private fun harness(scope: TestScope, journal: MutableList<String>): Pair<LiveProbe, ProbeTestClient> {
        val fake = ProbeTestClient(journal)
        // Réveil négatif aussitôt : cadence seule, vols sur la froide — un réveil
        // suspendu pendrait `runTest` (la coroutine de réveil ne terminerait pas).
        fake.healthGate = CompletableDeferred(
            Result.success(LiveHealth(ok = false, model = "sam3.1", device = "cuda", gpu = "A100")),
        )
        val probe = LiveProbe(
            scope = scope,
            clock = clock,
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
        )
        fake.publishedFrames = { probe.state.value.frames }
        probe.configure(ENDPOINT)
        probe.begin()
        // Scheduler coupé : ce test verrouille l'ordonnancement, pas les keyframes (voir LiveAdaptiveTest).
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        return probe to fake
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
    }
}
