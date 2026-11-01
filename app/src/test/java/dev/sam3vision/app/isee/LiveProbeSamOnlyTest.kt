package dev.sam3vision.app.isee

import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveTimings
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'écran iSee ne montre que SAM : les pastilles (`distanceLayer`) sont les
 * lectures de la dernière frame SAM, jamais des prédictions extrapolées entre
 * deux inférences (le ticker 80 ms n'existe plus dans la sonde).
 */
class LiveProbeSamOnlyTest {

    private var ns = 0L

    private fun frame(objects: List<SegObject> = emptyList(), elapsedMs: Long = 120L) =
        LiveFrame(width = 640, height = 480, elapsedMs = elapsedMs, objects = objects)

    /** Voiture mesurable au sténopé (même boîte que `ProbeFrameTest`). */
    private fun voiture() = SegObject(
        label = "car",
        score = 0.9f,
        box = listOf(270f, 200f, 370f, 280f),
        maskPngB64 = "",
    )

    @Test
    fun `pastilles = lectures SAM de la derniere frame`() = runTest {
        val probe = probe(this)
        val active = LiveSession(NeverClient(), clock = { ns })
        val shown = LiveTimings(clock = { ns })

        probe.publish(active, frame(listOf(voiture())), "A", 8_000_000L, shown)

        val layer = probe.state.value.distanceLayer
        assertTrue("une pastille SAM", layer != null && layer.readings.size == 1)
        assertEquals("car", layer!!.readings.single().label)
        assertEquals(voiture().box, layer.readings.single().box)
        assertEquals("l'écran montre ce que SAM a détecté", 1, probe.state.value.samDetections.size)
    }

    @Test
    fun `frame SAM vide = rien a l ecran`() = runTest {
        val probe = probe(this)
        val active = LiveSession(NeverClient(), clock = { ns })
        val shown = LiveTimings(clock = { ns })

        probe.publish(active, frame(listOf(voiture())), "A", 8_000_000L, shown)
        assertTrue(probe.state.value.distanceLayer != null)
        probe.publish(active, frame(), "B", 9_000_000L, shown)

        assertNull("SAM ne voit rien : aucune pastille", probe.state.value.distanceLayer)
    }

    @Test
    fun `entre deux frames, les pastilles restent gelees sur SAM`() = runTest {
        val probe = probe(this)
        val active = LiveSession(NeverClient(), clock = { ns })
        val shown = LiveTimings(clock = { ns })

        probe.publish(active, frame(listOf(voiture())), "A", 8_000_000L, shown)
        val gele = probe.state.value.distanceLayer
        // Au-delà de l'horizon d'oubli Kalman : une prédiction aurait bougé ou
        // disparu, une lecture SAM gelée est intacte.
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals("pas d'extrapolation entre deux inférences", gele, probe.state.value.distanceLayer)
    }

    private fun probe(scope: TestScope) = LiveProbe(
        scope = scope,
        clock = { ns },
        background = StandardTestDispatcher(scope.testScheduler),
        clientFor = { _, _, _ -> NeverClient() },
    ).also {
        it.configure("https://sam.test", announce = false, guide = true)
        it.begin()
    }

    /** Client qui ne répond jamais : `publish` est appelé directement. */
    private class NeverClient : LiveClient {
        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> = error("publish direct, pas de réseau")
        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}
