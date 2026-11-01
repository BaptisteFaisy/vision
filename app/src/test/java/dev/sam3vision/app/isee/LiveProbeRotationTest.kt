package dev.sam3vision.app.isee

import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PromptRotation
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation 3 groupes côté sonde : un groupe (~7 concepts, `person` partout)
 * par frame, réponses accumulées — voix et overlay lisent la fusion dans
 * l'état, jamais le groupe seul. Repli `ROTATION_OFF` : liste complète, sans
 * accumulation.
 */
class LiveProbeRotationTest {

    private fun obj(label: String) = SegObject(
        label = label, score = 0.9f, box = listOf(206f, 400f, 306f, 900f),
        maskPngB64 = "", maskEncoding = MASK_ENCODING_NONE,
    )

    private fun frame(vararg labels: String) = LiveFrame(
        width = 512, height = 1138, elapsedMs = 39L, objects = labels.map { obj(it) },
    )

    @Test
    fun `les envois tournent sur 3 groupes, person partout`() = runTest {
        val (probe, fake) = harness(this)
        val groups = PromptRotation.split(LivePrompts.default)
        send(probe, fake, "A", 8_000_000L, frame("person"))
        send(probe, fake, "B", 14_000_000L, frame("person"))
        send(probe, fake, "C", 20_000_000L, frame("person"))
        send(probe, fake, "D", 26_000_000L, frame("person"))
        assertEquals(listOf(groups[0], groups[1], groups[2], groups[0]), fake.sentPrompts)
        assertTrue(fake.sentPrompts.all { it?.contains("person") == true })
        assertEquals(listOf(8, 8, 7, 8), fake.sentPrompts.map { it?.size })
    }

    @Test
    fun `l'etat accumule  - la voiture du groupe 0 survit au groupe 1`() = runTest {
        val (probe, fake) = harness(this)
        val groups = PromptRotation.split(LivePrompts.default)
        assertTrue("précondition : voiture au groupe 0", groups[0].contains("car"))
        send(probe, fake, "A", 8_000_000L, frame("person", "car"))
        assertTrue(probe.state.value.samDetections.any { it.label == "car" })
        // Groupe 1 : pas de voiture envoyée, pas de voiture reçue — servie quand même.
        send(probe, fake, "B", 14_000_000L, frame("person"))
        val labels = probe.state.value.samDetections.map { it.label }
        assertTrue("voiture accumulée lue par voix/overlay : $labels", labels.contains("car"))
        assertTrue(labels.contains("person"))
    }

    @Test
    fun `negatif frais au 4e tour  - la voiture partie disparait de l'etat`() = runTest {
        val (probe, fake) = harness(this)
        send(probe, fake, "A", 8_000_000L, frame("person", "car"))
        send(probe, fake, "B", 14_000_000L, frame("person"))
        send(probe, fake, "C", 20_000_000L, frame("person"))
        // Groupe 0 de nouveau, voiture renvoyée sans objet : négatif frais.
        send(probe, fake, "D", 26_000_000L, frame("person"))
        assertTrue(probe.state.value.samDetections.none { it.label == "car" })
    }

    @Test
    fun `repli ROTATION_OFF  - liste complete a chaque frame, sans accumulation`() = runTest {
        val (probe, fake) = harness(this)
        probe.rotation.enabled = false
        send(probe, fake, "A", 8_000_000L, frame("person", "car"))
        send(probe, fake, "B", 14_000_000L, frame("person"))
        assertEquals(listOf(LivePrompts.default, LivePrompts.default), fake.sentPrompts)
        assertTrue(probe.state.value.samDetections.none { it.label == "car" })
    }

    @Test
    fun `reconfiguration rebatit les groupes et vide la memoire`() = runTest {
        val (probe, fake) = harness(this)
        send(probe, fake, "A", 8_000_000L, frame("person", "car"))
        assertTrue(probe.state.value.samDetections.any { it.label == "car" })
        probe.configure(PROBE_TEST_ENDPOINT, prompts = listOf("person", "door"))
        probe.begin()
        // Groupes rebâtis : person partout, porte au groupe 0 (round-robin).
        send(probe, fake, "B", 14_000_000L, frame("person"))
        assertEquals(listOf("person", "door"), fake.sentPrompts.last())
        assertTrue(probe.state.value.samDetections.none { it.label == "car" })
    }

    /** Une frame envoyée, répondue, publiée (`advanceUntilIdle` entre chaque). */
    private fun TestScope.send(probe: LiveProbe, fake: ProbeTestClient, jpeg: String, atNs: Long, answer: LiveFrame) {
        probe.onFrame(jpeg, captureAtNs = atNs)
        advanceUntilIdle()
        fake.respond(jpeg, answer)
        advanceUntilIdle()
    }

    /** Sonde branchée sur le faux client, réveil négatif, scheduler coupé. */
    private fun harness(scope: TestScope): Pair<LiveProbe, ProbeTestClient> {
        val fake = ProbeTestClient(mutableListOf())
        fake.healthGate = CompletableDeferred(
            Result.success(LiveHealth(ok = false, model = "sam3.1", device = "cuda", gpu = "A100")),
        )
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, _ -> fake },
        )
        probe.configure(PROBE_TEST_ENDPOINT)
        probe.begin()
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        return probe to fake
    }
}
