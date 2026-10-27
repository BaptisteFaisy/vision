package dev.sam3vision.core.net

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/** Type MIME de la vidéo envoyée à SAM 3.1 (MP4 issu de CameraX). */
private const val VIDEO_MEDIA = "video/mp4"

/** Le texte rendu à l'app ne dépasse jamais cette taille : l'UI n'a pas un pavé de 10 000 mots. */
private const val MAX_SUMMARY_CHARS = 600

/**
 * Client SAM 3.1 en mode **vidéo** : `POST {endpoint}/video` en `multipart/form-data`
 * (champ `video` = fichier, champ `prompt` = consigne optionnelle). Choix du multipart
 * plutôt que du base64 JSON : une vidéo pèse plusieurs Mo, le base64 gonflerait de 33 %
 * et saturerait la mémoire du téléphone.
 *
 * [onDeviceMode] documente l'autre branchement possible : si SAM 3.1 tourne en local sur
 * le téléphone, l'app n'appelle pas ce client — [sendVideo] refuse alors avec un message
 * explicite et l'UI annonce « Failed ». Un seul endroit change selon la réponse à la
 * question « cloud ou on-device ? » : le drapeau `ON_DEVICE_INFERENCE` de la config tap côté app.
 */
class HttpSam31VideoClient(
    private val baseUrl: String,
    private val onDeviceMode: Boolean = false,
    private val path: String = DEFAULT_PATH,
    private val client: OkHttpClient = defaultHttpClient(UPLOAD_TIMEOUT_SECONDS),
) : VideoClient {

    override suspend fun sendVideo(video: File, prompt: String?): Result<String> {
        if (onDeviceMode) return Result.failure(ApiError.BadResponse(INFERRED_ON_DEVICE))
        if (baseUrl.isBlank()) return Result.failure(ApiError.InvalidKey("endpoint SAM 3.1 vide"))
        if (!video.isFile || video.length() == 0L) return Result.failure(ApiError.BadResponse("vidéo absente ou vide"))
        // Multipart assemblé en IO : pas de stat fichier sur l'appelant.
        // L'échec de construction reste un BadResponse (contrat historique), pas un Network.
        return client.executeBuilt {
            runCatching { buildRequest(video, prompt) }.getOrElse {
                throw ApiError.BadResponse("vidéo illisible : ${it.message}")
            }
        }
            .then { outcome -> outcome.requireSuccess() }
            .then(::summarize)
    }

    override suspend fun health(): Result<Boolean> {
        if (baseUrl.isBlank()) return Result.success(false)
        val request = requestTo(baseUrl, "/health")
        return client.execute(request).map { outcome ->
            outcome.code in 200..299 && parse(outcome.body)?.optBoolean("ok", false) == true
        }
    }

    /** Multipart : le fichier est lu à la pièce, jamais chargé entier en mémoire. */
    private fun buildRequest(video: File, prompt: String?): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", DEFAULT_MODEL)
            .addFormDataPart("video", video.name, video.asRequestBody(VIDEO_MEDIA.toMediaType()))
            .apply { if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt) }
            .build()
        return requestTo(baseUrl, path, "POST", body)
    }

    companion object {
        const val DEFAULT_PATH = "/video"
        const val DEFAULT_MODEL = "sam3.1-video"
        const val UPLOAD_TIMEOUT_SECONDS: Long = 120L
        const val INFERRED_ON_DEVICE = "SAM 3.1 configuré pour l'inférence locale : envoi cloud désactivé"
    }
}

/** Champs texte connus d'une réponse vidéo, dans l'ordre de préférence. */
private val SUMMARY_KEYS = listOf("text", "description", "answer", "caption")

/** Message de remplacement quand le serveur répond 200 avec un corps vide. */
private const val EMPTY_SUMMARY = "réponse vide de SAM 3.1"

/**
 * Résumé d'une réponse vidéo : le premier champ texte connu du JSON, ou le corps brut quand
 * le serveur répond du **texte** (contrat tolérant, un backend peut renvoyer sa phrase nue).
 *
 * Un JSON **sans** champ texte est un diagnostic (`{"detail":…}` servi en 200) : il devient
 * [ApiError.BadResponse] au lieu d'être affiché tel quel à l'utilisateur.
 */
internal fun summarize(raw: String): Result<String> {
    val json = parse(raw) ?: return Result.success(clip(raw.trim().ifEmpty { EMPTY_SUMMARY }))
    val text = SUMMARY_KEYS.firstNotNullOfOrNull { key ->
        json.optString(key, "").takeIf { it.isNotBlank() }
    } ?: return Result.failure(ApiError.BadResponse("SAM 3.1 sans texte : ${raw.take(120)}"))
    return Result.success(clip(text))
}

/** Coupe au budget de l'UI : jamais un pavé de 10 000 mots dans le journal. */
private fun clip(text: String): String =
    if (text.length <= MAX_SUMMARY_CHARS) text else text.take(MAX_SUMMARY_CHARS) + "…"
