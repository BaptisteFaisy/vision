package dev.sam3vision.core.net

import dev.sam3vision.core.mask.base64Bytes
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import okhttp3.OkHttpClient

/**
 * Ancien délai dédié du direct (10 s), **conservé pour compatibilité** : il tuait la 1re
 * frame quand le conteneur Modal se réveillait (26,8 s mesurés). Le direct utilise
 * désormais [liveHttpClient] (read 60 s, sans `callTimeout`) ; le budget de la frame reste
 * appliqué par [WsLiveClient] (`withTimeoutOrNull`), qui sait elle seule ce qu'une frame vaut.
 */
const val LIVE_TIMEOUT_SECONDS: Long = 10L

/**
 * `/health` sert aussi de bouton « démarre la GPU » : la requête qui réveille un
 * conteneur Modal froid attend le démarrage (image + poids ≈ 30-50 s mesurés, jusqu'à
 * ~150 s si l'image doit être reconstruite). Avec le délai de 10 s du direct, chaque
 * réveil échouait avant la fin du démarrage : délai patient dédié.
 */
const val WAKE_TIMEOUT_SECONDS: Long = 180L

/** User-Agent propre au flux live, identifiable côté backend. */
internal const val LIVE_USER_AGENT = "$USER_AGENT live"

/**
 * Client de réveil, dérivé du pool du direct : le handshake `wss://` réutilise le tunnel
 * DNS/TCP/TLS déjà payé par `/health`. `newBuilder()` partage pool + dispatcher.
 */
private val wakeSharedClient: OkHttpClient by lazy {
    liveHttpClient().newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(WAKE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(WAKE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Client de l'endpoint `/live` du direct (backend Modal `sam3-live`, contrat docs/LIVE-SPEC.md).
 * Réutilise le client **partagé** [liveHttpClient] — un seul pool de connexions pour toute
 * l'app, donc DNS + TCP + TLS (71,3 ms mesurés) payés une fois —, le parsing de `Json.kt`
 * et les erreurs typées de `Errors.kt`. Le paramètre [client] reste injectable pour les tests.
 */
class HttpLiveClient(
    private val baseUrl: String,
    private val client: OkHttpClient = liveHttpClient(),
    private val wakeClient: OkHttpClient = wakeSharedClient,
    private val marks: WireMarks? = null,
    /** Format de masque demandé (`mask_format`) ; `null` = corps historique (`mask_encoding`). */
    private val maskFormat: String? = null,
    /** Côté long de la grille réduite (`mask_edge`) ; 0 = pleine résolution. */
    private val maskEdge: Int = 0,
    /**
     * Envoi binaire : JPEG brut (`octet-stream`, knobs en query) au lieu du JSON
     * base64 (−33 % à la montée). `false` par défaut : exige un backend qui sait
     * lire le binaire — à activer après son déploiement, jamais avant.
     */
    private val binaryUpload: Boolean = false,
) : LiveClient {

    /** Client effectif : exposé aux tests, qui prouvent le partage du pool. */
    internal val httpClient: OkHttpClient get() = client

    /**
     * Corps construit **dans** `Dispatchers.IO` (28 Ko de base64 jamais assemblés sur le
     * thread principal) et envoyé en [OneShotJsonBody] : jamais rejoué par OkHttp. La
     * réponse est lue en octets et parsée sans `String` intermédiaire.
     *
     * `marks` ne date que `/live` : `/health` réveille le conteneur hors chronologie, le
     * dater poserait des postes fantômes sur la frame en cours.
     */
    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        if (!binaryUpload) return client.executeBytesBuilt(marks) {
            val body = OneShotJsonBody(
                liveFrameJson(jpegB64, prompts, workEdge, maxObjects, maskFormat = maskFormat, maskEdge = maskEdge),
            )
            requestTo(baseUrl, "/live", "POST", body, LIVE_USER_AGENT)
        }.then { bytes -> catchingResult { parseLiveFrameBytes(bytes) } }
        val jpeg = base64Bytes(jpegB64)
            ?: return Result.failure(ApiError.BadResponse("/live : image_b64 illisible"))
        return client.executeBytesBuilt(marks) {
            request(
                liveBinaryUrl(baseUrl, prompts, workEdge, maxObjects, maskFormat, maskEdge),
                "POST",
                OneShotBytesBody(jpeg, OCTET_MEDIA),
                LIVE_USER_AGENT,
            )
        }.then { bytes -> catchingResult { parseLiveFrameBytes(bytes) } }
    }

    override suspend fun health(): Result<LiveHealth> {
        // Construite dans `executeBuilt` : une URL malformée devient une erreur, pas un crash.
        return wakeClient.executeBuilt { requestTo(baseUrl, "/health", userAgent = LIVE_USER_AGENT) }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseHealth(raw) } }
    }
}

/**
 * URL de `/live` binaire : mêmes knobs que le JSON, en query (`prompts` répété,
 * `work_edge`, `max_objects`, `mask_encoding`, `mask_format`, `mask_edge`).
 * `prompts` null = absent (le serveur applique ses défauts).
 */
internal fun liveBinaryUrl(
    baseUrl: String,
    prompts: List<String>?,
    workEdge: Int,
    maxObjects: Int,
    maskFormat: String?,
    maskEdge: Int,
): String {
    val query = StringBuilder()
    fun field(name: String, value: String) {
        if (query.isNotEmpty()) query.append('&')
        query.append(name).append('=').append(java.net.URLEncoder.encode(value, "UTF-8"))
    }
    prompts?.forEach { field("prompts", it) }
    field("work_edge", workEdge.toString())
    field("max_objects", maxObjects.toString())
    field("mask_encoding", dev.sam3vision.core.model.MASK_ENCODING_BITS)
    if (maskFormat != null) {
        field("mask_format", maskFormat)
        if (maskEdge > 0) field("mask_edge", maskEdge.toString())
    }
    return joinUrl(baseUrl, "/live") + "?" + query.toString()
}

private fun parseHealth(raw: String): LiveHealth {
    val json = parse(raw) ?: throw ApiError.BadResponse("/health : JSON illisible")
    return LiveHealth(
        ok = json.optBoolean("ok", false),
        model = json.str("model"),
        device = json.str("device"),
        gpu = json.str("gpu"),
    )
}
