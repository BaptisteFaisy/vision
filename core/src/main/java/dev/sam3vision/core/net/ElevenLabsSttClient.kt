package dev.sam3vision.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Repli de dictée : transcription ElevenLabs « Scribe »
 * (`POST {baseUrl}/speech-to-text`, multipart `file` + `model_id`, en-tête `xi-api-key`).
 *
 * Même contrat que le moteur Meta ([SpeechClient]) : un WAV PCM 16 bits mono en entrée,
 * le texte en sortie. Utilisée quand Meta échoue (facturation, panne, clé absente).
 * La clé est celle des réglages ElevenLabs : pas de nouveau secret à saisir.
 */
class ElevenLabsSttClient(
    private val apiKey: String,
    private val modelId: String = DEFAULT_STT_MODEL,
    private val baseUrl: String = DEFAULT_STT_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(),
) : SpeechClient {

    override suspend fun transcribe(wav: ByteArray): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("clé ElevenLabs absente — renseigne-la dans Réglages"))
        }
        if (wav.size <= WAV_HEADER_BYTES) {
            return Result.failure(ApiError.BadResponse("audio vide"))
        }
        return catchingResult { buildRequest(wav) }
            .then { http.execute(it) }
            .then { it.requireSuccess() }
            .then { raw -> catchingResult { parseText(raw) } }
    }

    private fun buildRequest(wav: ByteArray): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", modelId)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return requestTo(baseUrl, STT_PATH, "POST", body, headers = mapOf("xi-api-key" to apiKey))
    }

    /** `{"text": "..."}` — même extraction défensive que le moteur Meta. */
    internal fun parseText(raw: String): String =
        extractTranscript(raw) ?: throw ApiError.BadResponse("champ texte absent : ${raw.take(120)}")

    companion object {
        const val DEFAULT_STT_BASE_URL = "https://api.elevenlabs.io/v1"

        /** Modèle de transcription ElevenLabs (vérifié sur le compte le 26/10/2026). */
        const val DEFAULT_STT_MODEL = "scribe_v1"

        const val STT_PATH = "/speech-to-text"
        private const val WAV_HEADER_BYTES = 44
    }
}