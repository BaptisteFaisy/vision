package dev.sam3vision.app.tap

import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.net.VideoClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repli photo du tap : `POST /video` tenté d'abord, `POST /segment` sur la première
 * frame **uniquement** sur 404 de route manquante. Zéro réseau, zéro Android : les
 * clients sont des faux et l'extraction de frame est injectée.
 */
class TapPhotoFallbackTest {

    private val clip = File("/tmp/clip-fallback-test.mp4")

    @Test
    fun `video ok ne declenche aucun repli`() = runBlocking {
        val segment = FakeSamClient(Result.success(segmentOf("personne")))
        val sender = senderWithFallback(FakeVideoClient(Result.success("Un chat traverse.")), segment)

        assertEquals("Un chat traverse.", sender(clip).getOrNull())
        assertEquals(0, segment.calls)
    }

    @Test
    fun `404 de route manquante bascule sur segment`() = runBlocking {
        val segment = FakeSamClient(Result.success(segmentOf("personne", "chaise")))
        val sender = senderWithFallback(
            FakeVideoClient(Result.failure(ApiError.Http(404, "Not Found"))),
            segment,
            frames = { "fake-jpeg-b64" },
        )

        assertEquals("objets : personne (92 %), chaise (78 %)", sender(clip).getOrNull())
        assertEquals(1, segment.calls)
    }

    @Test
    fun `500 ne declenche aucun repli et remonte tel quel`() = runBlocking {
        val segment = FakeSamClient(Result.success(segmentOf("personne")))
        val sender = senderWithFallback(FakeVideoClient(Result.failure(ApiError.Http(500, "boom"))), segment)

        val failure = sender(clip).exceptionOrNull()
        assertTrue(failure is ApiError.Http)
        assertEquals(500, (failure as ApiError.Http).code)
        assertEquals(0, segment.calls)
    }

    @Test
    fun `gpu eteint ne declenche aucun repli`() = runBlocking {
        val segment = FakeSamClient(Result.success(segmentOf("personne")))
        val sender = senderWithFallback(FakeVideoClient(Result.failure(ApiError.BackendStopped())), segment)

        assertTrue(sender(clip).exceptionOrNull() is ApiError.BackendStopped)
        assertEquals(0, segment.calls)
    }

    @Test
    fun `frame illisible echoue explicitement sans appeler segment`() = runBlocking {
        val segment = FakeSamClient(Result.success(segmentOf("personne")))
        val sender = senderWithFallback(
            FakeVideoClient(Result.failure(ApiError.Http(404, "Not Found"))),
            segment,
            frames = { null },
        )

        val failure = sender(clip).exceptionOrNull()
        assertTrue(failure is ApiError.BadResponse)
        assertTrue(failure?.message.orEmpty().contains("première frame illisible"))
        assertEquals(0, segment.calls)
    }

    @Test
    fun `echec de segment remonte tel quel`() = runBlocking {
        val segment = FakeSamClient(Result.failure(ApiError.RateLimited()))
        val sender = senderWithFallback(
            FakeVideoClient(Result.failure(ApiError.Http(404, "Not Found"))),
            segment,
            frames = { "fake-jpeg-b64" },
        )

        assertTrue(sender(clip).exceptionOrNull() is ApiError.RateLimited)
    }

    @Test
    fun `segment vide donne aucun objet detecte`() {
        assertEquals("aucun objet détecté", segmentSummary(segmentOf()))
    }

    @Test
    fun `le resume cite au plus huit objets`() {
        val result = segmentSummary(segmentOf("a", "b", "c", "d", "e", "f", "g", "h", "i"))
        assertTrue(result.startsWith("objets : "))
        assertTrue(result.endsWith(", …"))
        assertTrue(!result.contains("i ("))
    }

    private fun segmentOf(vararg labels: String): SegmentResult {
        val scores = mapOf("personne" to 0.92f, "chaise" to 0.78f)
        return SegmentResult(
            width = 100,
            height = 100,
            objects = labels.map {
                SegObject(label = it, score = scores[it] ?: 0.5f, box = listOf(0f, 0f, 1f, 1f), maskPngB64 = "")
            },
        )
    }

    private class FakeVideoClient(private val outcome: Result<String>) : VideoClient {
        override suspend fun sendVideo(video: File, prompt: String?): Result<String> = outcome
        override suspend fun health(): Result<Boolean> = Result.success(true)
    }

    private class FakeSamClient(private val outcome: Result<SegmentResult>) : SamClient {
        var calls = 0
        override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult> {
            calls++
            return outcome
        }
        override suspend fun health(): Result<Boolean> = Result.success(true)
    }
}
