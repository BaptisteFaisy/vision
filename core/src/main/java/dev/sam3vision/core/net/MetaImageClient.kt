package dev.sam3vision.core.net

import dev.sam3vision.core.picto.IMAGE_MODEL
import dev.sam3vision.core.picto.IMAGE_SIZE_SQUARE
import dev.sam3vision.core.picto.pictoPrompt
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.RequestBody

/**
 * Pictogrammes Meta (`muse-image-1.0`) : la photo d'un objet devient un picto
 * noir sur jaune, via `POST {baseUrl}/images/edits` (multipart, compatible
 * OpenAI pour les champs, mais assemblé par [MetaMultipart] : Meta refuse le
 * `Content-Length` par part d'OkHttp, comme sur l'ASR).
 *
 * Même base URL et même clé que la dictée Meta : aucun réglage nouveau. Une
 * clé absente échoue sans réseau ; un 402 (facturation Meta non configurée)
 * devient [ApiError.PaymentRequired]. ~0,01 $ et ~10 s par image.
 */
class MetaImageClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = IMAGE_MODEL,
    private val http: OkHttpClient = defaultHttpClient(),
) : PictoImageClient {

    override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("Meta key missing — enter it in Settings"))
        }
        if (photoJpeg.isEmpty()) {
            return Result.failure(ApiError.BadResponse("empty photo"))
        }
        endpointRefusal("endpoint Meta", baseUrl, EDITS_PATH)?.let { return Result.failure(it) }
        return http.executeBuilt {
            requestTo(
                baseUrl,
                EDITS_PATH,
                "POST",
                multipart(photoJpeg, hint),
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
        }
            .then { it.requireSuccess() }
            .then { body -> catchingResult { decodeImage(body) } }
    }

    /** Champs texte puis fichier, dans l'ordre attendu : modèle, consigne, format. */
    private fun multipart(photoJpeg: ByteArray, hint: String): RequestBody =
        MetaMultipart()
            .text("model", model)
            .text("prompt", pictoPrompt(hint))
            .text("size", IMAGE_SIZE_SQUARE)
            .text("n", "1")
            .text("response_format", "b64_json")
            .text("output_format", "png")
            .file("image", "photo.jpg", "image/jpeg", photoJpeg)
            .build()

    /** PNG décodé depuis `data[0].b64_json` ; [ApiError.BadResponse] sinon. */
    internal fun decodeImage(raw: String): ByteArray {
        val b64 = extractImageB64(raw)
            ?: throw ApiError.BadResponse("image absente : ${raw.take(120)}")
        return runCatching { Base64.getDecoder().decode(b64) }
            .getOrElse { throw ApiError.BadResponse("image illisible (base64 invalide)") }
    }

    companion object {
        const val EDITS_PATH = "/images/edits"
    }
}
