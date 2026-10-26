package dev.sam3vision.core.net

import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.SegmentResult
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Client de l'endpoint SAM 3.1 (contrat de l'ancien backend Modal, sans clé).
 * `GET /health` et `POST /segment {"image_b64", "prompts"}`.
 */
class HttpSamClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(),
) : SamClient {

    override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult> {
        val body = JSONObject()
            .put("image_b64", pngOrJpegB64)
            .put("prompts", if (prompts == null) JSONObject.NULL else jsonArrayOf(prompts))
            .toString()
        val request = requestTo(baseUrl, "/segment", "POST", jsonBody(body))
        return client.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseSegment(raw) } }
    }

    override suspend fun health(): Result<Boolean> {
        val request = requestTo(baseUrl, "/health")
        return client.execute(request).map { outcome ->
            outcome.code in 200..299 && parse(outcome.body)?.optBoolean("ok", false) == true
        }
    }
}

private fun parseSegment(raw: String): SegmentResult {
    val json = parse(raw) ?: throw ApiError.BadResponse("/segment : JSON illisible")
    val objects = json.objects("objects").map { obj ->
        val fields = obj.maskedFields()
        SegObject(
            label = fields.label,
            score = fields.score,
            box = fields.box,
            maskPngB64 = fields.maskB64,
        )
    }
    return SegmentResult(width = json.intOr("width"), height = json.intOr("height"), objects = objects)
}
