package dev.sam3vision.core.usecase

import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.SpeechClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSpeech(private val outcome: Result<String>) : SpeechClient {
    var lastWav: ByteArray = ByteArray(0)
    var calls: Int = 0

    override suspend fun transcribe(wav: ByteArray): Result<String> {
        calls++
        lastWav = wav
        return outcome
    }
}

private fun oneSecondOfSilence(): ByteArray = ByteArray(Wav.SAMPLE_RATE * Wav.BYTES_PER_SAMPLE)

class DictationUseCaseTest {

    @Test
    fun `un enregistrement trop court est refuse sans appel reseau`() {
        val speech = FakeSpeech(Result.success("jamais"))
        val result = runBlocking { DictationUseCase(speech).invoke(ByteArray(200)) }
        assertTrue(result.exceptionOrNull() is ApiError.BadResponse)
        assertEquals(0, speech.calls)
    }

    @Test
    fun `le PCM part en WAV complet et le texte est rogne`() {
        val speech = FakeSpeech(Result.success("  bonjour  "))
        val text = runBlocking { DictationUseCase(speech).invoke(oneSecondOfSilence()) }.getOrThrow()
        assertEquals("bonjour", text)
        assertEquals(44 + oneSecondOfSilence().size, speech.lastWav.size)
        assertEquals("RIFF", String(speech.lastWav, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `un texte vide devient une erreur lisible`() {
        val speech = FakeSpeech(Result.success("   "))
        val error = runBlocking { DictationUseCase(speech).invoke(oneSecondOfSilence()) }.exceptionOrNull()
        assertTrue(error is ApiError.BadResponse)
        assertTrue(error?.message.orEmpty().contains("no speech"))
    }

    @Test
    fun `l'erreur du client est propagee telle quelle`() {
        val speech = FakeSpeech(Result.failure(ApiError.PaymentRequired()))
        val error = runBlocking { DictationUseCase(speech).invoke(oneSecondOfSilence()) }.exceptionOrNull()
        assertTrue(error is ApiError.PaymentRequired)
    }
}