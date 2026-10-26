package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Client factice - répond une fois par appel et compte les appels. */
private class FakeSpeech(
    private val outcome: Result<String>,
    private val label: String,
) : SpeechClient {
    var calls = 0

    override suspend fun transcribe(wav: ByteArray): Result<String> {
        calls++
        return outcome
    }

    override fun toString(): String = label
}

class FallbackSpeechClientTest {

    private val wav = ByteArray(120)

    @Test
    fun `primaire ok - le repli n'est pas appele`() = runBlocking {
        val primary = FakeSpeech(Result.success("meta"), "meta")
        val fallback = FakeSpeech(Result.success("eleven"), "eleven")
        val text = FallbackSpeechClient(primary, fallback).transcribe(wav).getOrThrow()

        assertEquals("meta", text)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `primaire en echec - le repli transcrit`() = runBlocking {
        val primary = FakeSpeech(Result.failure(ApiError.PaymentRequired()), "meta")
        val fallback = FakeSpeech(Result.success("eleven"), "eleven")
        val text = FallbackSpeechClient(primary, fallback).transcribe(wav).getOrThrow()

        assertEquals("eleven", text)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `les deux echouent - les deux messages remontent`() = runBlocking {
        val primary = FakeSpeech(Result.failure(ApiError.PaymentRequired()), "meta")
        val fallback = FakeSpeech(Result.failure(ApiError.InvalidKey("clé refusée")), "eleven")
        val error = FallbackSpeechClient(primary, fallback).transcribe(wav).exceptionOrNull()

        assertTrue(error is ApiError.AllProviders)
        assertTrue(error!!.message!!.contains("Meta :"))
        assertTrue(error.message!!.contains("ElevenLabs :"))
        assertTrue(error.message!!.contains("clé refusée"))
    }

    @Test
    fun `sans repli - l'erreur du primaire remonte telle quelle`() = runBlocking {
        val primary = FakeSpeech(Result.failure(ApiError.PaymentRequired()), "meta")
        val error = FallbackSpeechClient(primary, null).transcribe(wav).exceptionOrNull()

        assertTrue(error is ApiError.PaymentRequired)
    }
}