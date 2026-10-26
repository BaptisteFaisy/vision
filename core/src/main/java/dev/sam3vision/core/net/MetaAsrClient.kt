package dev.sam3vision.core.net

import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Transcription vocale Meta (`muse-voice-transcribe-1.0`).
 *
 * L'API n'est PAS compatible OpenAI : un seul point d'entrée,
 * `POST {baseUrl}/asr/transcribe`, en multipart — une part `request`
 * (JSON `{model, audioEncoding:"WAV"}`) et une part `audio` (WAV PCM 16 bits).
 *
 * Le multipart est écrit à la main : `MultipartBody` d'OkHttp ajoute un
 * `Content-Length` par part, que Meta refuse (`400 Malformed multipart body`).
 * La réponse est un JSON propre à Meta ; le texte est extrait défensivement.
 *
 * Une clé absente échoue sans réseau (comme le client LLM). Un 402 (facturation
 * Meta non configurée) devient [ApiError.PaymentRequired].
 */
class MetaAsrClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: OkHttpClient = defaultHttpClient(),
) : SpeechClient {

    override suspend fun transcribe(wav: ByteArray): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("clé Meta absente — renseigne-la dans Réglages"))
        }
        if (wav.size <= WAV_HEADER_BYTES) {
            return Result.failure(ApiError.BadResponse("audio vide"))
        }
        val request = requestTo(
            baseUrl,
            TRANSCRIBE_PATH,
            "POST",
            multipart(wav),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
        return http.execute(request)
            .then { it.requireSuccess() }
            .then { body -> catchingResult { parseTranscript(body) } }
    }

    /** Deux parts obligatoires : `request` (JSON) puis `audio` (WAV), sans en-tête de longueur. */
    private fun multipart(wav: ByteArray): RequestBody {
        val boundary = "----sam3vision" + UUID.randomUUID().toString().replace("-", "")
        val json = """{"model":"$model","audioEncoding":"WAV"}"""
        val out = ByteArrayOutputStream()
        out.write(("--$boundary$CRLF" + PART_REQUEST + json + CRLF).toByteArray(Charsets.UTF_8))
        out.write(("--$boundary$CRLF" + PART_AUDIO).toByteArray(Charsets.UTF_8))
        out.write(wav)
        out.write(("$CRLF--$boundary--$CRLF").toByteArray(Charsets.UTF_8))
        return out.toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
    }

    /** Texte de la réponse ; [ApiError.BadResponse] si le champ est introuvable. */
    internal fun parseTranscript(raw: String): String =
        extractTranscript(raw) ?: throw ApiError.BadResponse("champ texte absent : ${raw.take(120)}")

    companion object {
        const val DEFAULT_MODEL = "muse-voice-transcribe-1.0"
        const val TRANSCRIBE_PATH = "/asr/transcribe"

        private const val WAV_HEADER_BYTES = 44
        private const val CRLF = "\r\n"
        private const val PART_REQUEST =
            "Content-Disposition: form-data; name=\"request\"$CRLF" +
            "Content-Type: application/json$CRLF$CRLF"
        private const val PART_AUDIO =
            "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"$CRLF" +
            "Content-Type: audio/wav$CRLF$CRLF"
    }
}