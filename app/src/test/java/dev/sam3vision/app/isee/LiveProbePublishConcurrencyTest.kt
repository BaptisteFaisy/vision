package dev.sam3vision.app.isee

import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publications concurrentes : `runFrame` lance la frame suivante avant de
 * publier (débit GPU), donc deux publications se recouvrent — avec des
 * instances partagées (`FrameLabels`, trackers, veto, chronologie), ça tuait
 * l'app (`ArrayIndexOutOfBounds` dans `FrameLabels.of`, constaté sur
 * émulateur le 01/11). La section publication est sérialisée.
 */
class LiveProbePublishConcurrencyTest {

    private var ns = 0L

    private fun frame(objects: List<SegObject>): LiveFrame =
        LiveFrame(width = 512, height = 1138, elapsedMs = 120L, objects = objects)

    /** Objet sans masque (décodage Bitmap impossible en JVM, inutile ici). */
    private fun objet(label: String, x0: Float) = SegObject(
        label = label,
        score = 0.9f,
        box = listOf(x0, 400f, x0 + 100f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    @Test
    fun `des publications concurrentes ne plantent pas la sonde`() = runBlocking(Dispatchers.Default) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val probe = LiveProbe(
                scope = scope,
                clock = { ns },
                background = Dispatchers.Default,
                clientFor = { _, _, _ -> NeverClient() },
            ).also {
                it.configure("https://sam.test", announce = false, guide = true)
                it.begin()
            }
            val active = LiveSession(NeverClient(), clock = { ns })
            val errors = ConcurrentLinkedQueue<Throwable>()
            val jobs = List(8) { t ->
                launch(Dispatchers.Default) {
                    try {
                        repeat(60) { i ->
                            val at = 8_000_000L + (t * 60L + i) * 500_000L
                            val shown = dev.sam3vision.core.live.LiveTimings(clock = { ns })
                            probe.publish(
                                active,
                                frame(
                                    listOf(
                                        objet("person", 206f),
                                        objet("car", 10f),
                                        objet("bus", 320f),
                                        objet("traffic light", 420f),
                                    ),
                                ),
                                "A",
                                at,
                                shown,
                            )
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
            jobs.forEach { it.join() }
            assertTrue("plantage concurrent : ${errors.peek()}", errors.isEmpty())
        } finally {
            scope.cancel()
        }
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
