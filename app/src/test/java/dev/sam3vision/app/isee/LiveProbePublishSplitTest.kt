package dev.sam3vision.app.isee

import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveTimings
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publication en deux écritures (pixels d'abord) : l'écriture #1 porte les
 * masques et la chronologie (`pixelSeq` avance, `frames` non), l'écriture #2
 * les chiffres et la voix (`frames` avance). Les guetteurs voix, déclenchés
 * sur `frames`, ne voient jamais de chiffres périmés ; l'écran accroche le
 * poste PIXEL à `pixelSeq`, sans attendre la phrase.
 */
class LiveProbePublishSplitTest {

    private var ns = 0L

    private fun frame(objects: List<SegObject> = emptyList(), elapsedMs: Long = 120L) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = objects)

    /** Personne sans masque (décodage Bitmap impossible en JVM, inutile ici). */
    private fun personne() = SegObject(
        label = "person",
        score = 0.9f,
        box = listOf(206f, 400f, 306f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    @Test
    fun `pixels avant chiffres, frames avec les chiffres`() = runTest {
        val probe = probe(this)
        val active = LiveSession(NeverClient(), clock = { ns })
        val shown = LiveTimings(clock = { ns })

        val before = probe.state.value
        probe.publishPixels(active, frame(listOf(personne())), shown)
        val pixels = probe.state.value
        assertEquals(1, pixels.pixelSeq)
        assertEquals("frames n'avance qu'en #2", 0, pixels.frames)
        assertTrue("pas de détections en #1", pixels.samDetections.isEmpty())
        // Avis d'armement (markWarming) : #1 le laisse intact, sans émission.
        assertEquals("phrase inchangée en #1", before.spoken, pixels.spoken)
        assertEquals("pas d'émission voix en #1", before.spokenSeq, pixels.spokenSeq)
        assertTrue("chrono posée en #1", pixels.timings.known)

        val rest = probe.computeRest(frame(listOf(personne())), "A", 8_000_000L)
        probe.publishRest(active, frame(listOf(personne())), rest)
        val chiffres = probe.state.value
        assertEquals(1, chiffres.frames)
        assertEquals("pixelSeq ne bouge plus en #2", 1, chiffres.pixelSeq)
        assertTrue("détections en #2", chiffres.samDetections.isNotEmpty())
    }

    @Test
    fun `publish complet garde les compteurs alignes`() = runTest {
        val probe = probe(this)
        val active = LiveSession(NeverClient(), clock = { ns })
        val shown = LiveTimings(clock = { ns })

        probe.publish(active, frame(), "A", 8_000_000L, shown)
        probe.publish(active, frame(), "B", 14_000_000L, shown)

        assertEquals(2, probe.state.value.frames)
        assertEquals(2, probe.state.value.pixelSeq)
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

    /** Client qui ne répond jamais : `publish*` est appelé directement. */
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
