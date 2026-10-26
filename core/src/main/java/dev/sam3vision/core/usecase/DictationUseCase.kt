package dev.sam3vision.core.usecase

import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.net.then

/**
 * Dictée : PCM brut du micro -> WAV -> transcription Meta -> texte propre.
 * Refuse localement les enregistrements trop courts (avant tout appel réseau)
 * et un texte vide (« aucune parole détectée »).
 */
class DictationUseCase(
    private val speech: SpeechClient,
    private val minDurationMs: Long = MIN_DURATION_MS,
    private val sampleRate: Int = Wav.SAMPLE_RATE,
) {

    suspend fun invoke(pcm: ByteArray): Result<String> {
        val durationMs = Wav.durationMs(pcm.size, sampleRate)
        if (durationMs < minDurationMs) {
            return Result.failure(
                ApiError.BadResponse("enregistrement trop court (${durationMs} ms, minimum ${minDurationMs} ms)"),
            )
        }
        return speech.transcribe(Wav.pcm16(pcm, sampleRate)).then { raw ->
            val text = raw.trim()
            if (text.isEmpty()) {
                Result.failure(ApiError.BadResponse("aucune parole détectée"))
            } else {
                Result.success(text)
            }
        }
    }

    companion object {
        const val MIN_DURATION_MS = 400L
    }
}