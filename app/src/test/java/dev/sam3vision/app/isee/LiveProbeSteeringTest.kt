package dev.sam3vision.app.isee

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WireMarks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La page caméra suit le cap demandé à la voix : « Gauche. » sur voie
 * libre, passage TwinLite devant un obstacle, et chemin publié dans l'état
 * pour la flèche vidéo. Sans objet à décorer ni masque à décoder, sans Android.
 */
class LiveProbeSteeringTest {

    private var ns = 0L

    private fun frame(objects: List<SegObject> = emptyList(), elapsedMs: Long = 120L) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = objects)

    /** Personne à gauche du champ (centre 0,21), sans masque (inutile à la consigne). */
    private fun personneGauche() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(56f, 400f, 156f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    @Test
    fun `cap gauche dit gauche sur voie libre`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.setSteering(SteeringCap.Left)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()
        fake.respond("B", frame())
        advanceUntilIdle()

        assertEquals("Left.", probe.state.value.spoken)
        assertEquals(SteeringCap.Left, probe.state.value.steeringCap)
    }

    @Test
    fun `cap gauche bouche dit le passage TwinLite`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.setSteering(SteeringCap.Left)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(listOf(personneGauche())))
        advanceUntilIdle()
        fake.respond("B", frame(listOf(personneGauche())))
        advanceUntilIdle()

        assertEquals("straight ahead.", probe.state.value.spoken)
    }

    @Test
    fun `chemin publie dans l etat pour la video`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.setSteering(SteeringCap.Left)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(listOf(personneGauche())))
        advanceUntilIdle()

        val cue = probe.state.value.steering
        assertTrue("chemin attendu dans l'état", cue != null)
        assertEquals(SteeringCap.Left, cue?.cap)
        assertEquals(true, cue?.blocked)
        assertEquals(SteeringCap.Straight, cue?.arrowDir())
    }

    @Test
    fun `sans guidage pas de chemin dans l etat`() = runTest {
        val (probe, fake) = harness(this, guide = false, announce = true)
        probe.setSteering(SteeringCap.Left)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(listOf(personneGauche())))
        advanceUntilIdle()

        assertNull(probe.state.value.steering)
    }

    @Test
    fun `nouvelle session retombe a tout droit`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.setSteering(SteeringCap.Left)
        probe.begin()
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()
        fake.respond("B", frame())
        advanceUntilIdle()

        assertEquals(SteeringCap.Straight, probe.state.value.steeringCap)
        assertEquals("Straight.", probe.state.value.spoken)
    }

    /** Sonde branchée sur un faux client : les réponses SAM sont livrées par le test. */
    private fun harness(scope: TestScope, guide: Boolean, announce: Boolean = false): Pair<LiveProbe, FakeLiveClient> {
        val fake = FakeLiveClient()
        val probe = LiveProbe(
            scope = scope,
            clock = { ns },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
        )
        probe.configure("https://sam.test", announce = announce, guide = guide)
        probe.begin()
        return probe to fake
    }

    /** Client de test : chaque `live` attend que le test livre sa réponse. */
    private class FakeLiveClient : LiveClient {

        var marks: WireMarks? = null

        private val waiting = mutableMapOf<String, CompletableDeferred<Result<LiveFrame>>>()

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            val slot = CompletableDeferred<Result<LiveFrame>>()
            waiting[jpegB64] = slot
            return slot.await()
        }

        fun respond(jpeg: String, frame: LiveFrame) {
            val slot = waiting.remove(jpeg) ?: error("aucune frame « $jpeg » en attente")
            marks?.onBody()
            slot.complete(Result.success(frame))
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}
