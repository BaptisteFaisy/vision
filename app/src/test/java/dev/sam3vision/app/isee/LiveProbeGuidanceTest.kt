package dev.sam3vision.app.isee

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La page caméra iSee guide la marche quand le réglage est actif : « Tout droit. »
 * sur voie libre, voix TwinLite devant un obstacle — au lieu de l'annonce
 * des positions. Sans objet à décorer ni masque à décoder, sans Android.
 */
class LiveProbeGuidanceTest {

    private var ns = 0L

    private fun frame(objects: List<SegObject> = emptyList(), elapsedMs: Long = 120L) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = objects)

    /** Personne au centre du champ, sans masque (inutile à la consigne). */
    private fun personne() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(206f, 400f, 306f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    /** Gobelet centré à ~0,4 m (sténopé 10 cm / 90 px) : contact mesuré. */
    private fun gobelet() = SegObject(
        label = "cup",
        score = 0.9f,
        box = listOf(206f, 500f, 306f, 590f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    /** Personne centrée à ~4 m (sténopé 1,70 m / 219 px sur frame portrait). */
    private fun personneLoin() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(206f, 400f, 306f, 619f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    @Test
    fun `guidage dit tout droit sur voie libre`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()
        fake.respond("B", frame())
        advanceUntilIdle()

        assertEquals("Straight.", probe.state.value.spoken)
    }

    @Test
    fun `guidage dit TwinLite devant un obstacle`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()
        probe.onFrame("C", captureAtNs = 20_000_000L)
        advanceUntilIdle()
        fake.respond("B", frame(listOf(personne())))
        advanceUntilIdle()
        fake.respond("C", frame(listOf(personne())))
        advanceUntilIdle()

        assertEquals("left.", probe.state.value.spoken)
    }

    @Test
    fun `mots doux et positions non urgents, stop seul urgent`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()
        fake.respond("B", frame())
        advanceUntilIdle()

        assertEquals("Straight.", probe.state.value.spoken)
        assertFalse(probe.state.value.spokenUrgent)

        probe.onFrame("C", captureAtNs = 20_000_000L)
        advanceUntilIdle()
        fake.respond("C", frame(listOf(personne())))
        advanceUntilIdle()

        assertEquals("left.", probe.state.value.spoken)
        assertFalse(probe.state.value.spokenUrgent)

        val (sonde, faux) = harness(this, guide = false, announce = true)
        sonde.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        sonde.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        faux.respond("A", frame(listOf(personne())))
        advanceUntilIdle()
        faux.respond("B", frame(listOf(personne())))
        advanceUntilIdle()

        assertNotNull(sonde.state.value.spoken)
        assertFalse(sonde.state.value.spokenUrgent)
    }

    @Test
    fun `stop interne au contact mesure = urgent`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(listOf(gobelet())))
        advanceUntilIdle()
        fake.respond("B", frame(listOf(gobelet())))
        advanceUntilIdle()

        assertEquals("obstacle, stop.", probe.state.value.spoken)
        assertTrue(probe.state.value.spokenUrgent)
    }

    @Test
    fun `portrait le couloir projette avec le champ vertical`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(listOf(personneLoin())))
        advanceUntilIdle()
        fake.respond("B", frame(listOf(personneLoin())))
        advanceUntilIdle()

        // Frame portrait 512×1138 : l'axe x porte le champ vertical (~52,8°),
        // pas 67° — le décalage chiffré passe de 1,5 m à 1,0 m, la voix reste « left. ».
        assertEquals("left.", probe.state.value.spoken)
        assertEquals(1.0f, probe.voice.lastDecision?.shiftMeters!!, 0.001f)
    }

    @Test
    fun `detour publie pour le GPS`() = runTest {
        val (probe, fake) = harness(this, guide = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()

        assertFalse(probe.state.value.detour)

        fake.respond("B", frame(listOf(personne())))
        advanceUntilIdle()

        assertTrue(probe.state.value.detour)
    }

    @Test
    fun `sans guidage la voie libre reste muette`() = runTest {
        val (probe, fake) = harness(this, guide = false, announce = true)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 14_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame())
        advanceUntilIdle()
        fake.respond("B", frame())
        advanceUntilIdle()

        assertNull(probe.state.value.spoken)
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
