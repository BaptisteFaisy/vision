package dev.sam3vision.core.net

import okhttp3.OkHttpClient
import okhttp3.RequestBody

/**
 * Transcription vocale Meta (`muse-voice-transcribe-1.0`).
 *
 * L'API n'est PAS compatible OpenAI : un seul point d'entrée,
 * `POST {baseUrl}/asr/transcribe`, en multipart — une part `request`
 * (JSON `{model, audioEncoding:"WAV"}`) et une part `audio` (WAV PCM 16 bits).
 *
 * Le multipart est écrit à la main ([MetaMultipart], partagé avec l'image) :
 * `MultipartBody` d'OkHttp ajoute un `Content-Length` par part, que Meta refuse
 * (`400 Malformed multipart body`). La réponse est un JSON propre à Meta ; le
 * texte est extrait défensivement.
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
        // Construite dans `executeBuilt` : une URL malformée devient une erreur, pas un crash.
        return http.executeBuilt {
            requestTo(
                baseUrl,
                TRANSCRIBE_PATH,
                "POST",
                multipart(wav),
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
        }
            .then { it.requireSuccess() }
            .then { body -> catchingResult { parseTranscript(body) } }
    }

    /** Deux parts obligatoires : `request` (JSON) puis `audio` (WAV), sans en-tête de longueur. */
    private fun multipart(wav: ByteArray): RequestBody {
        val json = """{"model":"$model","audioEncoding":"WAV"}"""
        return MetaMultipart()
            .json("request", json)
            .file("audio", "audio.wav", "audio/wav", wav)
            .build()
    }

    /** Texte de la réponse ; [ApiError.BadResponse] si le champ est introuvable. */
    internal fun parseTranscript(raw: String): String =
        extractTranscript(raw) ?: throw ApiError.BadResponse("champ texte absent : ${raw.take(120)}")

    companion object {
        const val DEFAULT_MODEL = "muse-voice-transcribe-1.0"
        const val TRANSCRIBE_PATH = "/asr/transcribe"

        private const val WAV_HEADER_BYTES = 44
    }
}