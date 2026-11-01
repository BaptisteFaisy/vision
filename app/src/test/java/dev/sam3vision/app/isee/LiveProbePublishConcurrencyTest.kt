package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vols qui se recouvrent : `runFrame` lance la frame suivante avant de
 * publier (débit GPU), donc deux publications partagent les instances de
 * la sonde (`FrameLabels`, trackers, veto, chronologie) — ça tuait l'app
 * (`ArrayIndexOutOfBounds` dans `FrameLabels.of`, émulateur le 01/11).
 * La section publication est sérialisée, le réseau reste parallèle.
 */
class LiveProbePublishConcurrencyTest {

    private var ns = 0L

    /** Objet sans masque (décodage Bitmap impossible en JVM, inutile ici). */
    private fun objet(label: String, x0: Float) = SegObject(
        label = label,
        score = 0.9f,
        box = listOf(x0, 400f, x0 + 100f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    private fun frame() = LiveFrame(
        width = 512,
        height = 1138,
        elapsedMs = 120L,
        objects = listOf(
            objet("person", 206f),
            objet("car", 10f),
            objet("bus", 320f),
            objet("traffic light", 420f),
        ),
    )

    @Test
    fun `des frames qui se recouvrent ne plantent pas la publication`() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val handler = CoroutineExceptionHandler { _, e ->
            if (e is CancellationException) return@CoroutineExceptionHandler
            errors.add(e)
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)
        try {
            val probe = LiveProbe(
                scope = scope,
                clock = { ns },
                background = Dispatchers.Default,
                clientFor = { _, _, _ -> QuickClient(frame()) },
            ).also {
                it.configure("https://sam.test", announce = false, guide = true)
                it.begin()
                it.onForeground()
            }
            val start = CountDownLatch(1)
            val threads = List(8) {
                Thread {
                    start.await()
                    repeat(40) { i ->
                        try {
                            probe.onFrame("A$i")
                        } catch (e: Throwable) {
                            errors.add(e)
                        }
                    }
                }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join(60_000) }
            runBlocking { delay(3_000) }
            assertTrue("plantage concurrent : ${errors.peek()}", errors.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /** Client qui répond aussitôt : les vols se recouvrent au maximum. */
    private class QuickClient(private val frame: LiveFrame) : LiveClient {
        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> = Result.success(frame)
        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}
