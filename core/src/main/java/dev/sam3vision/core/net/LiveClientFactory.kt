package dev.sam3vision.core.net

/**
 * Client du direct : **WebSocket uniquement**, avec le délai qu'on veut.
 *
 * `master` a abandonné `POST /live` pour les frames : un seul handshake par session et
 * une trame par frame au lieu de deux (~31 ms par frame). Le client HTTP ne reste que
 * pour `/health` (réveil du conteneur), délégué par [WsLiveClient].
 *
 * **Tout partage un seul `OkHttpClient`** ([liveHttpClient]) : DNS + TCP + TLS
 * (71,3 ms mesurés) ne sont plus payés à chaque `start()` / `restartWith()`. Le
 * transport WebSocket en dérive par `newBuilder()` ([liveWsClient]), donc le handshake
 * `wss://` réutilise le tunnel chaud du `/health`.
 *
 * Le délai du **handshake** suit celui de la frame : mesuré sur le téléphone, un
 * transport figé à 10 s échouait pendant les 26-50 s de réveil du conteneur. Une seule
 * fabrique pour les deux écrans qui parlent à SAM (Direct et page caméra iSee).
 */
fun liveClient(
    baseUrl: String,
    timeoutSeconds: Long = LIVE_TIMEOUT_SECONDS,
    marks: WireMarks? = null,
): LiveClient = liveClientWith(baseUrl, timeoutSeconds, marks) { milliseconds ->
    OkHttpWsTransport(liveWsClient(milliseconds / 1000))
}

/** Fabrique interne, avec le transport injectable : testée sans réseau ni GPU. */
internal fun liveClientWith(
    baseUrl: String,
    timeoutSeconds: Long,
    marks: WireMarks? = null,
    transportFor: (Long) -> WsTransport,
): LiveClient = WsLiveClient(
    baseUrl,
    transport = transportFor(timeoutSeconds * 1000),
    timeoutMs = timeoutSeconds * 1000,
    // Seul `/health` passe par ce client : il utilise les clients partagés par défaut.
    http = HttpLiveClient(baseUrl),
    marks = marks,
)