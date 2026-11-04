package dev.sam3vision.core.net

import dev.sam3vision.core.model.SegmentResult
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Client de l'endpoint SAM 3.1 (contrat de l'ancien backend Modal, sans clé).
 * `GET /health` et `POST /segment {"image_b64", "prompts"}`.
 */
class HttpSamClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(),
    /** Attente avant le 2e essai DNS en bascule lunettes (injectée pour les tests). */
    private val routeRetryDelayMs: Long = HANDOVER_RETRY_DELAY_MS,
) : SamClient {

    override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult> {
        endpointRefusal("endpoint SAM", baseUrl, "/segment")?.let { return Result.failure(it) }
        val first = attemptSegment(pngOrJpegB64, prompts)
        val error = first.exceptionOrNull() ?: return first
        // 404 Modal ambigu (fenêtre de déploiement, restore proxy) : le proxy n'a
        // déclenché aucune inférence, un seul nouvel essai est gratuit et évite un
        // faux « SAM GPU off ». Tout autre échec remonte tel quel (pas de 2e inférence).
        if (error is ApiError.BackendStopped) return attemptSegment(pngOrJpegB64, prompts)
        // Bascule lunettes : le DNS a suivi la route morte, le 2e essai part sur
        // la route installée (rien n'a été envoyé : zéro inférence rejouée).
        if (!isHandoverTransient(error)) return first
        delay(routeRetryDelayMs)
        return attemptSegment(pngOrJpegB64, prompts)
    }

    /**
     * Une tentative de segmentation : corps assemblé dans Dispatchers.IO (base64 ~100 Ko
     * jamais scanné sur l'appelant) et non rejouable — pas de 2e inférence GPU sur
     * keep-alive mort.
     */
    private suspend fun attemptSegment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult> =
        client.executeBuilt {
            requestTo(baseUrl, "/segment", "POST", OneShotJsonBody(segmentJson(pngOrJpegB64, prompts)))
        }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseSegment(raw) } }

    override suspend fun health(): Result<Boolean> {
        endpointRefusal("endpoint SAM", baseUrl, "/health")?.let { return Result.failure(it) }
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
