package dev.sam3vision.core.net

import dev.sam3vision.core.model.SegmentResult
import okhttp3.OkHttpClient

/**
 * Client de l'endpoint SAM 3.1 (contrat de l'ancien backend Modal, sans clé).
 * `GET /health` et `POST /segment {"image_b64", "prompts"}`.
 */
class HttpSamClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(),
) : SamClient {

    override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult> {
        // Corps assemblé dans Dispatchers.IO (base64 ~100 Ko jamais scanné sur l'appelant)
        // et non rejouable : pas de 2e inférence GPU sur keep-alive mort.
        return client.executeBuilt {
            requestTo(baseUrl, "/segment", "POST", OneShotJsonBody(segmentJson(pngOrJpegB64, prompts)))
        }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseSegment(raw) } }
    }

    override suspend fun health(): Result<Boolean> {
        // Construite dans `executeBuilt` : une URL malformée devient une erreur, pas un crash.
        return client.executeBuilt { requestTo(baseUrl, "/health") }.map { outcome ->
            outcome.code in 200..299 && parse(outcome.body)?.optBoolean("ok", false) == true
        }
    }
}

private fun parseSegment(raw: String): SegmentResult {
    val json = parse(raw) ?: throw ApiError.BadResponse("/segment : JSON illisible")
    // Même lecture d'objet que le direct ([segObject]) : `mask_encoding`, `mask_scale` et
    // `mask_poly` sont lus ici comme là-bas, avec le défaut PNG de cet endpoint.
    val objects = json.objects("objects").map { obj -> obj.segObject() }
    return SegmentResult(width = json.intOr("width"), height = json.intOr("height"), objects = objects)
}

/** Corps `/segment` manuel : base64 brut (alphabet sans échappement), prompts échappés. */
internal fun segmentJson(imageB64: String, prompts: List<String>?): String {
    val out = StringBuilder(imageB64.length + 128)
    out.append("{\"image_b64\":\"").append(imageB64).append("\",\"prompts\":")
    if (prompts == null) {
        out.append("null")
    } else {
        out.append('[')
        prompts.forEachIndexed { index, prompt ->
            if (index > 0) out.append(',')
            out.append('"').append(jsonEscape(prompt)).append('"')
        }
        out.append(']')
    }
    out.append('}')
    return out.toString()
}
