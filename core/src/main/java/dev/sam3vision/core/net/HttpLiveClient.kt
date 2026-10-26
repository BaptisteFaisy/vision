package dev.sam3vision.core.net

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

/** Client de réveil, **partagé** lui aussi : le tunnel reste chaud entre deux appels. */
private val wakeSharedClient: OkHttpClient by lazy { defaultHttpClient(WAKE_TIMEOUT_SECONDS) }

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
    ): Result<LiveFrame> = client.executeBytesBuilt(marks) {
        val body = OneShotJsonBody(
            liveFrameJson(jpegB64, prompts, workEdge, maxObjects, maskFormat = maskFormat, maskEdge = maskEdge),
        )
        requestTo(baseUrl, "/live", "POST", body, LIVE_USER_AGENT)
    }.then { bytes -> catchingResult { parseLiveFrameBytes(bytes) } }

    override suspend fun health(): Result<LiveHealth> {
        val request = requestTo(baseUrl, "/health", userAgent = LIVE_USER_AGENT)
        return wakeClient.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseHealth(raw) } }
    }
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
