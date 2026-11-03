package dev.sam3vision.app.media

import dev.sam3vision.core.net.SpeechClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSpeech : SpeechClient {
    override suspend fun transcribe(wav: ByteArray): Result<String> = Result.success("gare de Lyon")
}

/**
 * Le relâchement ne coupe pas la queue de dictée (« gare de Ly ») : `stop`
 * laisse une grâce au micro avant de figer le PCM. En JVM le micro est
 * absent (PCM vide, refusé sans appel réseau) — seule la grâce est mesurée.
 */
class HoldTranscriptTest {

    @Test
    fun `stop attend la grace avant de couper`() = runBlocking {
        val hold = HoldTranscript()
        hold.start()
        val startMs = System.currentTimeMillis()
        val result = hold.stop(FakeSpeech())
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(result.isFailure)
        assertTrue("grâce $elapsedMs ms < 250 ms", elapsedMs >= 250)
    }
}
