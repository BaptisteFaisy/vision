package dev.sam3vision.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Format de sortie demandé à ElevenLabs : MP3 44,1 kHz / 128 kbit/s. */
private const val OUTPUT_FORMAT = "mp3_44100_128"

/** Taille maximale inspectée pour deviner si le corps est de l'audio ou du texte. */
private const val SNIFF_BYTES = 4_096

/**
 * Synthèse vocale ElevenLabs (`POST {baseUrl}/text-to-speech/{voiceId}`).
 * La clé vient des réglages, jamais du code. Le corps de la réponse est de l'audio brut
 * (MP3), d'où [OkHttpClient.executeBytes] plutôt que [OkHttpClient.execute].
 *
 * Erreurs typées : 401 → [ApiError.InvalidKey], 402 (compte sans crédits) →
 * [ApiError.PaymentRequired], 429 → [ApiError.RateLimited], le reste → [ApiError.Http].
 * Un texte vide ou une clé absente échoue sans aucun appel réseau.
 */
class ElevenLabsTtsClient(
    private val apiKey: String,
    private val voiceId: String,
    private val baseUrl: String = DEFAULT_TTS_BASE_URL,
    private val modelId: String = DEFAULT_TTS_MODEL,
    private val http: OkHttpClient = defaultHttpClient(),
) : TtsClient {

    override suspend fun synthesize(text: String): Result<ByteArray> {
        val refusal = localRefusal(text)?.let { Result.failure<ByteArray>(it) }
        if (refusal != null) return refusal
        return catchingResult { buildRequest(text) }
            .then { http.executeBytes(it) }
            .then(::ensureAudio)
    }

    /** Refus locaux, avant tout appel réseau : message déjà affichable dans l'UI. */
    private fun localRefusal(text: String): ApiError? = when {
        text.isBlank() -> ApiError.BadResponse("nothing to read: empty text")
        apiKey.isBlank() -> ApiError.InvalidKey("ElevenLabs key missing — enter it in Settings")
        voiceId.isBlank() -> ApiError.BadResponse("ElevenLabs voice missing — enter it in Settings")
        else -> null
    }

    /** Requête complète : URL encodée par OkHttp, clé ElevenLabs et corps JSON. */
    private fun buildRequest(text: String): Request =
        request(ttsUrl(), "POST", jsonBody(ttsJson(text)), headers = mapOf("xi-api-key" to apiKey))

    /**
     * `{baseUrl}/text-to-speech/{voiceId}?output_format=mp3_44100_128` : les segments
     * sont ajoutés par [okhttp3.HttpUrl.Builder], qui échappe proprement le voiceId.
     */
    private fun ttsUrl(): String = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("text-to-speech")
        .addPathSegment(voiceId.trim())
        .addQueryParameter("output_format", OUTPUT_FORMAT)
        .build()
        .toString()

    /** Corps attendu par ElevenLabs, réglages de voix inclus. */
    private fun ttsJson(text: String): String {
        val voiceSettings = JSONObject()
            .put("stability", 0.5)
            .put("similarity_boost", 0.75)
            .put("style", 0.0)
            .put("use_speaker_boost", true)
        return JSONObject()
            .put("text", text)
            .put("model_id", modelId)
            .put("voice_settings", voiceSettings)
            .toString()
    }

    companion object {
        const val DEFAULT_TTS_BASE_URL = "https://api.elevenlabs.io/v1"

        /** Modèle multilingue rapide, accepté en compte free (vérifié le 26/10/2026). */
        const val DEFAULT_TTS_MODEL = "eleven_v4_turbo"
    }
}

/**
 * Un 2xx doit contenir de l'audio. Un corps vide, ou qui est du **texte lisible** (JSON ou
 * XML d'erreur, page HTML, « Bad Gateway » nu) renvoyé avec un code 200, est traité comme un
 * échec lisible au lieu d'être joué comme une voix.
 */
private fun ensureAudio(bytes: ByteArray): Result<ByteArray> =
    if (bytes.isNotEmpty() && !looksLikeText(bytes)) {
        Result.success(bytes)
    } else {
        Result.failure(ApiError.BadResponse("empty audio received from ElevenLabs"))
    }

/**
 * Vrai si les premiers octets sont du **texte** (donc pas de l'audio) : tout le fenêtrage
 * se décode en UTF-8 sans octet de remplacement et sans caractère de contrôle.
 *
 * L'ancienne devinette ne regardait que `{`, `[` et `<` : un corps « Bad Gateway » nu passait
 * pour du MP3 et partait dans le lecteur. Un MP3, lui, commence par `ID3` puis des octets
 * binaires (0xFF, 0xFB…), donc il échoue à ce test — et reste joué tel quel.
 */
private fun looksLikeText(bytes: ByteArray): Boolean {
    val head = bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)).toString(Charsets.UTF_8)
    if (REPLACEMENT_CHAR in head) return false
    return head.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
}

/** Caractère posé par le décodeur UTF-8 sur un octet non décodable. */
private const val REPLACEMENT_CHAR = '\uFFFD'
