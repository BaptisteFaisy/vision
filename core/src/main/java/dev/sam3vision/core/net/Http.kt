package dev.sam3vision.core.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** User-Agent identifiable côté backend. */
internal const val USER_AGENT = "sam3-vision-android/1.0"

/** Nom exact de l'en-tête posé par [request] sur toutes les requêtes sortantes. */
private const val USER_AGENT_HEADER = "User-Agent"

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Délai par défaut imposé par la spec : 60 s. */
const val DEFAULT_TIMEOUT_SECONDS: Long = 60L

/**
 * Socle HTTP **partagé** : un seul `ConnectionPool` + `Dispatcher` pour LLM, SAM photo,
 * tracker, ASR, TTS et vidéo. Avant, chaque `defaultHttpClient()` fabriquait un pool neuf
 * (71,3 ms mesurés de DNS+TCP+TLS à chaque premier appel). `newBuilder()` partage le pool
 * et le dispatcher par défaut, donc les variantes de timeouts restent chaudes.
 */
private val sharedBaseClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 12
            },
        )
        .build()
}

/** Connect long = fail-slow (60 s avant) ; 10 s suffit, le read garde le budget long. */
private const val DEFAULT_CONNECT_SECONDS = 10L

/** Un seul endroit qui fabrique le client HTTP (pool partagé, timeouts par usage). */
fun defaultHttpClient(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OkHttpClient {
    if (timeoutSeconds == DEFAULT_TIMEOUT_SECONDS) return sharedBaseClient
    return sharedBaseClient.newBuilder()
        .connectTimeout(minOf(timeoutSeconds, DEFAULT_CONNECT_SECONDS), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
}

/**
 * Client **partagé** du direct : un seul `OkHttpClient` pour toute l'app, donc un seul
 * `ConnectionPool` et un seul cache TLS. Un client neuf coûte 71,3 ms mesurés (DNS 3,4 +
 * TCP 32,4 + TLS 35,5) et chaque `start()` / `restartWith()` en fabriquait un, alors que la
 * caméra tourne déjà et que la 1re frame part aussitôt.
 *
 * Délais réglés sur la réalité du direct :
 * - connect 3 s (un lien chaud qui ne répond pas en 3 s est mort) ;
 * - write 5 s (27 Ko de frame JPEG s'écrivent en quelques ms) ;
 * - read 60 s : le **réveil** du conteneur Modal est mesuré à 20-27 s ;
 * - callTimeout 0 : aucun plafond global. L'ancien plafond de 10 s tuait la 1re frame après
 *   sommeil du conteneur (26,8 s mesurés) tout en facturant l'inférence quand même.
 *
 * `retryOnConnectionFailure(false)` : une frame n'est jamais renvoyée (voir [OneShotJsonBody]) ;
 * un keep-alive mort échoue franchement au lieu de rejouer 28 Ko et une 2e inférence GPU.
 */
private val liveSharedClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(LIVE_CONNECT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(LIVE_WRITE_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LIVE_READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // 8 connexions idle / 5 min : marge pour 1 frame en vol + `/health`, et tunnel chaud
        // entre deux sessions rapprochées (défaut OkHttp : 5 idle).
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()
}

/** Le client HTTP du direct : **toujours le même** (pool, DNS, TLS partagés). */
fun liveHttpClient(): OkHttpClient = liveSharedClient

/**
 * Client du **WebSocket** du direct : `newBuilder()` ne refait pas un client à neuf — il
 * **partage le pool de connexions et le dispatcher** de [liveHttpClient], donc le handshake
 * `wss://` bénéficie du DNS/TCP/TLS déjà payés par `/health` et les frames HTTP.
 *
 * Seul le délai de lecture est ajusté : le handshake d'un conteneur endormi attend le
 * démarrage, il ne doit pas être coupé à 10 s. Le budget de la **frame** reste celui de
 * [WsLiveClient] (`withTimeoutOrNull`) — le socket, lui, vit toute la session.
 */
fun liveWsClient(timeoutSeconds: Long = LIVE_TIMEOUT_SECONDS): OkHttpClient =
    liveSharedClient.newBuilder()
        .readTimeout(maxOf(timeoutSeconds, LIVE_READ_SECONDS), TimeUnit.SECONDS)
        .build()

/**
 * Client du WebSocket Scribe realtime : même pool que le direct (`newBuilder`
 * garde connexions et dispatcher), mais **sans délai de lecture** — une tenue
 * silencieuse ne doit pas tuer le socket à 60 s. La session est bornée par
 * l'app (commit au relâchement, abort à l'annulation).
 */
fun scribeWsClient(): OkHttpClient =
    liveWsClient().newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

private const val LIVE_CONNECT_SECONDS = 3L
private const val LIVE_WRITE_SECONDS = 5L
private const val LIVE_READ_SECONDS = 60L

/**
 * Client **partagé** du tracker : mêmes délais que [defaultHttpClient] pour 15 s,
 * mais `retryOnConnectionFailure(false)` comme le direct — une frame du tracker n'est
 * jamais renvoyée (voir [OneShotJsonBody]) ; `newBuilder()` partage pool + dispatcher.
 */
private val trackSharedClient: OkHttpClient by lazy {
    defaultHttpClient(TRACK_TIMEOUT_SECONDS).newBuilder()
        .retryOnConnectionFailure(false)
        .build()
}

/** Le client HTTP du tracker : **toujours le même** (pool, DNS, TLS partagés). */
fun trackHttpClient(): OkHttpClient = trackSharedClient

/**
 * Client **partagé** de l'extinction (`POST /release`) : mêmes délais que
 * [defaultHttpClient] pour 45 s, mais sans rejeu — le contrat est **une seule
 * requête par appel**, un refus ne part jamais deux fois.
 */
private val releaseSharedClient: OkHttpClient by lazy {
    defaultHttpClient(RELEASE_TIMEOUT_SECONDS).newBuilder()
        .retryOnConnectionFailure(false)
        .build()
}

/** Le client HTTP de `/release` : **toujours le même**, sans rejeu. */
fun releaseHttpClient(): OkHttpClient = releaseSharedClient

/** Réponse HTTP brute. */
internal data class HttpOutcome(val code: Int, val body: String)

/**
 * Corps JSON **non rejouable** : OkHttp ne relance jamais un POST dont le corps est déjà
 * parti. Sans ça, une connexion keep-alive morte fait re-POSTer la frame (28 Ko + une 2e
 * inférence GPU facturée). Les octets sont encodés une fois, à la construction.
 */
internal class OneShotJsonBody(json: String) : RequestBody() {

    private val bytes: ByteArray = json.toByteArray(Charsets.UTF_8)

    override fun contentType(): MediaType = JSON_MEDIA

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    /** Un corps one-shot n'est jamais rejoué par OkHttp. */
    override fun isOneShot(): Boolean = true
}

/** Corps binaire **non rejouable** : même protection que [OneShotJsonBody] pour `/live` binaire. */
internal class OneShotBytesBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType,
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    override fun isOneShot(): Boolean = true
}

/** Type MIME d'une frame envoyée en binaire (JPEG brut, knobs en query). */
internal val OCTET_MEDIA = "application/octet-stream".toMediaType()

internal fun joinUrl(baseUrl: String, path: String): String =
    baseUrl.trim().trimEnd('/') + path

/**
 * `true` si [url] est une URL http(s) complète et parsable par OkHttp : exactement ce
 * que `Request.Builder().url(...)` accepte sans lever. Une base stockée sans scheme
 * (copiée sans `https://`) rend `false` au lieu de laisser remonter le message brut du
 * parseur (`Expected URL scheme…`) jusqu'à l'écran.
 */
fun isUsableHttpUrl(url: String): Boolean = url.trim().toHttpUrlOrNull() != null

/**
 * Message partagé des refus d'endpoint : chaque client garde son type historique,
 * seul le texte change — le jargon du parseur OkHttp (`Expected URL scheme…`) ne
 * remonte plus à l'écran, l'utilisateur est guidé vers Réglages.
 */
fun invalidEndpointMessage(label: String): String =
    "$label invalid (https://…) — fix it in Settings"

/**
 * Refus local partagé d'un endpoint inutilisable : `null` quand l'URL exacte de
 * l'appel passe le parseur OkHttp. Une base vide ou sans scheme (saisie en
 * Réglages) devient une erreur actionnable, sans aucun appel réseau.
 */
fun endpointRefusal(label: String, baseUrl: String, path: String): ApiError.Network? {
    if (baseUrl.isBlank()) return ApiError.Network("$label missing — enter it in Settings")
    if (!isUsableHttpUrl(joinUrl(baseUrl, path))) return ApiError.Network(invalidEndpointMessage(label))
    return null
}

/**
 * **Fabrique unique de requêtes** : URL, méthode, corps, en-têtes.
 *
 * Avant, chaque client construisait son `Request.Builder` à la main — dix fois, avec
 * autant de façons d'oublier le `User-Agent` ou le `Content-Type`. Ici, une seule
 * implémentation existe : impossible d'oublier un en-tête commun, et le comportement
 * observé par le backend ne change pas (mêmes noms, mêmes valeurs, mêmes méthodes).
 *
 * L'ordre relatif des en-têtes n'a pas d'importance (RFC 9110 §5.3 : l'ordre des
 * champs de noms différents n'est pas significatif) ; `User-Agent` passe donc en tête.
 *
 * @param body `null` = requête sans corps (GET, DELETE).
 *
 * Lève sur URL malformée (`Request.Builder.url()`) : toujours construire DANS
 * `executeBuilt` / `catchingResult`, jamais en argument direct de `execute`.
 */
internal fun request(
    url: String,
    method: String = "GET",
    body: RequestBody? = null,
    userAgent: String = USER_AGENT,
    headers: Map<String, String> = emptyMap(),
): Request {
    val builder = Request.Builder().url(url).header(USER_AGENT_HEADER, userAgent)
    headers.forEach { (name, value) -> builder.header(name, value) }
    return builder.method(method, body).build()
}

/** [request] sur une base + un chemin joints comme [joinUrl] (base sans slash final). */
internal fun requestTo(
    base: String,
    path: String,
    method: String = "GET",
    body: RequestBody? = null,
    userAgent: String = USER_AGENT,
    headers: Map<String, String> = emptyMap(),
): Request = request(joinUrl(base, path), method, body, userAgent, headers)

/**
 * Corps JSON `application/json; charset=utf-8` : un seul endroit connaît le
 * `Content-Type` du JSON, les clients ne le redéclarent plus.
 */
internal fun jsonBody(json: String): RequestBody = json.toRequestBody(JSON_MEDIA)

/** Exécute un appel bloquant sur `Dispatchers.IO` et normalise toutes les erreurs. */
private suspend fun <T> ioResult(block: () -> T): Result<T> =
    withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e.toApiError())
        }
    }

/**
 * Exécute une requête **construite dans** `Dispatchers.IO` : le corps JSON du direct
 * (~28 Ko de base64) est assemblé hors du thread principal, qui doit rester libre pour
 * la capture et le rendu.
 */
internal suspend fun OkHttpClient.executeBuilt(build: () -> Request): Result<HttpOutcome> =
    ioResult { newCall(build()).execute().use { HttpOutcome(it.code, it.body?.string().orEmpty()) } }

/** Exécute une requête déjà construite hors du thread principal. */
internal suspend fun OkHttpClient.execute(request: Request): Result<HttpOutcome> =
    executeBuilt { request }

internal fun Throwable.toApiError(): ApiError = when (this) {
    is ApiError -> this
    is SocketTimeoutException -> ApiError.Timeout()
    is InterruptedIOException -> ApiError.Timeout()
    is IOException -> ApiError.Network(cause = this)
    else -> ApiError.Network(message = message ?: "backend unreachable", cause = this)
}

/** 401/402/409/429 ont des messages dédiés, le reste tombe dans [ApiError.Http]. */
internal fun httpError(code: Int, body: String): ApiError = when (code) {
    401, 403 -> ApiError.InvalidKey(if (code == 403) "access refused (403)" else "invalid key (401)")
    402 -> ApiError.PaymentRequired()
    // 409 du proxy LLM : la requête a été refusée pour une limite côté service (quota ou
    // requête concurrente) — le corps brut ne dit pas laquelle, le message reste actionnable.
    409 -> ApiError.RateLimited("AI-side limit reached (409): try again in a moment")
    429 -> ApiError.RateLimited()
    // Modal répond 404 « modal-http: invalid function call » quand l'app n'est pas déployée
    // (arrêtée) : message actionnable, et surtout pas de nouvelle tentative.
    404 -> if (body.contains("invalid function call", ignoreCase = true)) {
        ApiError.BackendStopped()
    } else {
        ApiError.Http(code, body)
    }

    else -> ApiError.Http(code, body)
}

/**
 * Comme [executeBuilt], mais conserve les octets bruts : le direct s'en sert pour parser
 * sans `String` intermédiaire (les réponses portent les masques), le TTS pour le corps audio.
 * Le corps n'est jamais converti en String sauf en cas d'erreur, où il sert de message.
 *
 * [marks] date les deux postes que le transport seul peut voir : en-têtes reçus, puis
 * corps complet **avant** parsing — c'est ce qui sépare `descente` de `parse` dans la
 * chronologie de l'écran. Passer `null` (le cas de `/health` et du TTS) ne date rien.
 */
internal suspend fun OkHttpClient.executeBytesBuilt(
    marks: WireMarks? = null,
    build: () -> Request,
): Result<ByteArray> = ioResult {
    newCall(build()).execute().use { response ->
        marks?.onHeaders()
        val bytes = response.body?.bytes() ?: ByteArray(0)
        marks?.onBody()
        if (response.code in 200..299) {
            bytes
        } else {
            // Levée ApiError : reprise telle quelle par ioResult.
            throw httpError(response.code, bytes.toString(Charsets.UTF_8))
        }
    }
}

internal suspend fun OkHttpClient.executeBytes(request: Request): Result<ByteArray> =
    executeBytesBuilt { request }

/** Vérifie un code 2xx, sinon renvoie l'erreur typée correspondante. */
internal fun HttpOutcome.requireSuccess(): Result<String> =
    if (code in 200..299) Result.success(body) else Result.failure(httpError(code, body))

/** Enveloppe un bloc qui lève [ApiError] (ou autre) en `Result`. */
internal inline fun <R> catchingResult(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: ApiError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(ApiError.BadResponse(e.message ?: "unreadable response"))
    }


/** Chaînon : garde l'erreur de gauche, enchaîne une opération qui renvoie déjà un Result. */
internal inline fun <T, R> Result<T>.then(block: (T) -> Result<R>): Result<R> =
    fold(onSuccess = { block(it) }, onFailure = { Result.failure(it) })
