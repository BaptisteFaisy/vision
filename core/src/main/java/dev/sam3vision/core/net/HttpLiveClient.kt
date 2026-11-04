package dev.sam3vision.core.net

import dev.sam3vision.core.mask.base64Bytes
import dev.sam3vision.core.mask.base64Text
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Délai normal d'une frame du direct (5 s, ~60× le p99 serveur de 83 ms) : une frame qui
 * dépasse a un problème réseau, pas GPU — l'abandon rapide rend la main à la suivante et
 * les stats récupèrent 2× plus vite. La 1re frame (réveil Modal 30-50 s) passe par le
 * délai froid (60 s, sonde iSee) ; le budget est appliqué par [WsLiveClient].
 */
const val LIVE_TIMEOUT_SECONDS: Long = 5L

/**
 * `/health` sert aussi de bouton « démarre la GPU » : la requête qui réveille un
 * conteneur Modal froid attend le démarrage (image + poids ≈ 30-50 s mesurés, jusqu'à
 * ~150 s si l'image doit être reconstruite). Avec le délai de 5 s du direct, chaque
 * réveil échouerait avant la fin du démarrage : délai patient dédié.
 */
const val WAKE_TIMEOUT_SECONDS: Long = 180L

/** User-Agent propre au flux live, identifiable côté backend. */
internal const val LIVE_USER_AGENT = "$USER_AGENT live"

/** Attente avant le 2e essai de `/health` (transitoire) : même barème que le LLM. */
private const val HEALTH_RETRY_DELAY_MS = 1_500L

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
    /** Masques demandés (`mask_encoding`) ; `false` = boîtes et scores seuls (`none`). */
    private val masks: Boolean = true,
    /** Profondeur fusionnée (`with_depth`) : médiane de proximité par objet en réponse. */
    private val withDepth: Boolean = false,
    /** Attente avant le 2e essai de `/health` (transitoire) : injectée pour les tests. */
    private val retryDelayMs: Long = HEALTH_RETRY_DELAY_MS,
    /** Attente avant le 2e essai DNS en bascule lunettes (injectée pour les tests). */
    private val routeRetryDelayMs: Long = HANDOVER_RETRY_DELAY_MS,
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
        endpointRefusal("endpoint direct", baseUrl, "/live")?.let { return Result.failure(it) }
        val first = attemptLive(jpegB64, prompts, workEdge, maxObjects)
        val error = first.exceptionOrNull() ?: return first
        // Bascule lunettes : le DNS a suivi la route morte, le 2e essai part sur
        // la route installée (rien n'a été envoyé : zéro inférence rejouée).
        if (!isHandoverTransient(error)) return first
        delay(routeRetryDelayMs)
        return attemptLive(jpegB64, prompts, workEdge, maxObjects)
    }

    /** Une tentative de frame : corps neuf à chaque essai (jamais de corps rejoué). */
    private suspend fun attemptLive(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        if (!binaryUpload) return client.executeBytesBuilt(marks) {
            val body = OneShotJsonBody(
                liveFrameJson(jpegB64, prompts, workEdge, maxObjects, maskFormat = maskFormat, maskEdge = maskEdge, withDepth = withDepth, masks = masks),
            )
            requestTo(baseUrl, "/live", "POST", body, LIVE_USER_AGENT)
        }.then { bytes -> catchingResult { parseLiveFrameBytes(bytes) } }
        val jpeg = base64Bytes(jpegB64)
            ?: return Result.failure(ApiError.BadResponse("/live : image_b64 illisible"))
        return postBytes(jpeg, prompts, workEdge, maxObjects)
    }

    /**
     * JPEG brut : en `binaryUpload`, `POST` octets **sans** détour base64 ; sinon le JSON
     * du contrat (le base64 fait partie du corps historique, pas d'un détour client).
     */
    override suspend fun liveBytes(
        jpeg: ByteArray,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        endpointRefusal("endpoint direct", baseUrl, "/live")?.let { return Result.failure(it) }
        // JSON : [live] rejoue déjà lui-même le DNS mort (un seul retry par appel).
        if (!binaryUpload) return live(base64Text(jpeg), prompts, workEdge, maxObjects)
        if (jpeg.isEmpty()) return Result.failure(ApiError.BadResponse("/live : image vide"))
        val first = postBytes(jpeg, prompts, workEdge, maxObjects)
        val error = first.exceptionOrNull() ?: return first
        if (!isHandoverTransient(error)) return first
        delay(routeRetryDelayMs)
        return postBytes(jpeg, prompts, workEdge, maxObjects)
    }

    /** `POST` octets : JPEG brut, knobs en query — le corps binaire du direct. */
    private suspend fun postBytes(
        jpeg: ByteArray,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> = client.executeBytesBuilt(marks) {
        request(
            liveBinaryUrl(baseUrl, prompts, workEdge, maxObjects, maskFormat, maskEdge, masks),
            "POST",
            OneShotBytesBody(jpeg, OCTET_MEDIA),
            LIVE_USER_AGENT,
        )
    }.then { bytes -> catchingResult { parseLiveFrameBytes(bytes) } }

    /**
     * Sonde + bouton de réveil : un échec transitoire (503 de boot, bascule de
     * déploiement, hoquet réseau) ou un DNS mort en bascule lunettes
     * ([isHandoverTransient]) est rejoué **une fois** — 2 appels au plus.
     * GET idempotent : jamais une inférence rejouée, contrairement aux frames.
     */
    override suspend fun health(): Result<LiveHealth> {
        endpointRefusal("endpoint direct", baseUrl, "/health")?.let { return Result.failure(it) }
        val first = probeHealth()
        val error = first.exceptionOrNull()
        if (error == null || (!error.isTransient && !isHandoverTransient(error))) return first
        delay(retryDelayMs)
        return probeHealth()
    }

    /** Une tentative de sonde, URL construite dans `executeBuilt` (jamais de crash). */
    private suspend fun probeHealth(): Result<LiveHealth> =
        wakeClient.executeBuilt { requestTo(baseUrl, "/health", userAgent = LIVE_USER_AGENT) }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseHealth(raw) } }
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
    masks: Boolean = true,
): String {
    val query = StringBuilder()
    fun field(name: String, value: String) {
        if (query.isNotEmpty()) query.append('&')
        query.append(name).append('=').append(java.net.URLEncoder.encode(value, "UTF-8"))
    }
    prompts?.forEach { field("prompts", it) }
    field("work_edge", workEdge.toString())
    field("max_objects", maxObjects.toString())
    field("mask_encoding", if (masks) MASK_ENCODING_BITS else MASK_ENCODING_NONE)
    if (masks && maskFormat != null) {
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
        wsBinary = json.optBoolean("ws_binary", false),
    )
}
