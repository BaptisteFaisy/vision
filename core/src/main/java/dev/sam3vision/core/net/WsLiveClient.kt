package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Direct par WebSocket : un message JSON par frame sur `/live/ws`, réponse portant le
 * **même `id`**. Mesuré côté Modal : 50,3 ms en WebSocket contre 81,4 ms en POST, soit
 * ~31 ms gagnées par frame.
 *
 * Corrélation : une **file par `id`** (et non une file FIFO) — le serveur peut répondre
 * dans le désordre sans ambiguïté, et un message dont l'`id` ne correspond à aucune
 * requête en vol est simplement ignoré. Le health n'a rien à voir avec le direct : il
 * est délégué au client HTTP [http], donc aucune duplication de l'appel `/health`.
 *
 * Coût client sur le chemin critique : le thread lecteur d'OkHttp ne **parse rien** — il lit
 * l'`id` en tête de message ([readId], via [leadingId]) et transmet le texte brut. La trame
 * n'est convertie en JSON qu'une fois, côté appelant ([readJson]), et cet objet unique sert à
 * la fois à la garde `error` et à la construction de [LiveFrame].
 */
class WsLiveClient(
    private val baseUrl: String,
    private val transport: WsTransport = OkHttpWsTransport(liveWsClient()),
    private val timeoutMs: Long = LIVE_TIMEOUT_SECONDS * 1000,
    private val http: LiveClient = HttpLiveClient(baseUrl),
    /** Jalons réseau pour la chronologie de l'écran (facultatif : les tests n'en posent pas). */
    private val marks: WireMarks? = null,
    /**
     * Lecture de l'`id` sur le thread lecteur : ciblée en tête de message, **sans construction
     * de JSON**. Injectée pour que les tests prouvent qu'une trame ignorée ne coûte **zéro**
     * conversion.
     */
    private val readId: (String) -> Int = { leadingId(it) },
    /**
     * Lecture du JSON d'une réponse : **une seule fois par trame**, sur le thread de l'appelant.
     * Injectée pour que les tests comptent les conversions.
     */
    private val readJson: (String) -> JSONObject? = { parse(it) },
    /** Format de masque demandé (`mask_format`) ; `null` = corps historique (`mask_encoding`). */
    private val maskFormat: String? = null,
    /** Côté long de la grille réduite (`mask_edge`) ; 0 = pleine résolution. */
    private val maskEdge: Int = 0,
    /** Profondeur fusionnée (`with_depth`) : médiane de proximité par objet en réponse. */
    private val withDepth: Boolean = false,
    /** Masques demandés (`mask_encoding`) ; `false` = boîtes et scores seuls (`none`). */
    private val masks: Boolean = true,
) : LiveClient {

    private val lock = Any()

    /** Réponses attendues, indexées par `id` de requête. */
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private var nextId = 0
    private var connected = false

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val id = synchronized(lock) { ++nextId }
        val reply = CompletableDeferred<String>()
        // Message (~28 Ko) assemblé hors verrou : `onText` (même lock) ne doit pas attendre.
        val message = frameMessage(id, jpegB64, prompts, workEdge, maxObjects)
        val opened = synchronized(lock) {
            // L'attente est enregistrée **avant** l'ouverture : un échec de socket
            // (connexion refusée) peut survenir immédiatement, il doit réveiller la frame.
            pending[id] = reply
            if (!openLocked()) {
                pending.remove(id)
                false
            } else {
                true
            }
        }
        if (!opened) return Result.failure(ApiError.Network(WS_UNREACHABLE))
        // Envoi **hors verrou** : pendant que ~28 Ko partent sur le socket, le thread
        // lecteur (`onText`, même verrou) continue de réveiller les frames en vol —
        // sinon un envoi lent figeait toutes les réceptions. Si le socket meurt entre
        // l'ouverture et l'envoi, `send` refuse (ou `onClosed` a déjà réveillé la
        // frame en échec) : les deux chemins rendent un échec réseau, jamais un succès.
        val sent = transport.send(message)
        if (!sent) {
            synchronized(lock) { pending.remove(id) }
            return Result.failure(ApiError.Network("WebSocket /live/ws: send refused"))
        }
        val raw = awaitReply(reply)
        synchronized(lock) { pending.remove(id) }
        return raw.then { text -> parseReply(text) }
    }

    /** Vrai tant que le socket est ouvert — l'UI s'en sert pour dire « WebSocket » ou « KO ». */
    val isConnected: Boolean get() = synchronized(lock) { connected }

    /** Le WebSocket ne sert pas au health : un seul appel `/health`, celui du client HTTP. */
    override suspend fun health(): Result<LiveHealth> = http.health()

    override fun close() {
        transport.close()
        synchronized(lock) { connected = false }
        failPending(ApiError.Network(WS_CLOSED))
    }

    /** Ouvre la connexion si besoin (appel sous [lock]). */
    private fun openLocked(): Boolean {
        if (connected) return true
        connected = transport.open(
            webSocketUrl(baseUrl, LIVE_WS_PATH),
            { text -> onText(text) },
            { error -> onClosed(error) },
            // Handshake accepté : poste « en-têtes » de la session (absent en WebSocket
            // pour les frames suivantes — un socket déjà ouvert n'a plus d'en-têtes à lire).
            { marks?.onHeaders() },
        )
        return connected
    }

    /**
     * Message de requête : **exactement** le corps de `POST /live` (mêmes champs, mêmes
     * défauts, `mask_encoding` compris — voir [liveFrameJson]), plus l'`id` de corrélation et,
     * quand ils sont demandés, les knobs du codec masques (`mask_format`, `mask_edge`) et
     * la profondeur fusionnée (`with_depth`).
     */
    private fun frameMessage(
        id: Int,
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): String = liveFrameJson(jpegB64, prompts, workEdge, maxObjects, id, maskFormat, maskEdge, withDepth, masks)

    /**
     * Réponse du serveur : même forme que `POST /live`, ou `error` (connexion maintenue).
     * Le texte n'est converti en JSON **qu'ici, une fois** : la garde `error` et la frame
     * partagent le même objet.
     */
    private fun parseReply(raw: String): Result<LiveFrame> {
        val json = readJson(raw) ?: return Result.failure(ApiError.BadResponse(WS_UNREADABLE))
        return liveReplyOf(json)
    }

    /**
     * Message entrant, sur le thread lecteur d'OkHttp : seule la requête portant cet `id` est
     * réveillée. L'`id` est lu en tête de message ([leadingId]) sans construction de JSON —
     * la trame (jusqu'à ~5 Ko de masques) ne coûte ici que quelques caractères parcourus.
     */
    private fun onText(text: String) {
        val reply = synchronized(lock) { pending[readId(text)] } ?: return
        // Corps (message) complet, avant le parsing de la réponse par l'appelant : c'est le
        // poste « corps » de la chronologie. Un message orphelin ne date rien.
        marks?.onBody()
        reply.complete(text)
    }

    /** Socket fermé ou en panne : les frames en vol échouent tout de suite. */
    private fun onClosed(error: Throwable?) {
        synchronized(lock) { connected = false }
        failPending(error?.toApiError() ?: ApiError.Network(WS_CLOSED))
    }

    private fun failPending(failure: Throwable) {
        val waiting = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { reply -> reply.completeExceptionally(failure) }
    }

    private suspend fun awaitReply(reply: CompletableDeferred<String>): Result<String> = try {
        val raw = withTimeoutOrNull(timeoutMs) { reply.await() }
        if (raw == null) {
            dropDeadSocket()
            Result.failure(ApiError.Timeout())
        } else Result.success(raw)
    } catch (e: ApiError) {
        Result.failure(e)
    }

    /**
     * Un délai dépassé sans message de fermeture = socket **apparemment** vivant mais
     * silencieusement mort (le conteneur a été arrêté, redéployé, la passerelle a changé
     * de cible). Sans ce nettoyage, `connected` resterait vrai et chaque frame suivante
     * attendrait son délai dans le vide — c'est ce qui figeait l'écran à
     * « réveil du GPU en cours » avec 0 masque, alors que `/health` répondait en 0,1 s.
     *
     * On ferme et on marque fermé : la frame suivante **rouvre** le handshake.
     */
    private fun dropDeadSocket() {
        val wasOpen = synchronized(lock) {
            val open = connected
            connected = false
            open
        }
        if (wasOpen) runCatching { transport.close() }
    }

    private companion object {
        const val WS_UNREACHABLE = "WebSocket /live/ws unreachable"
        const val WS_CLOSED = "WebSocket /live/ws closed"
        const val WS_UNREADABLE = "WebSocket: unreadable message"
    }
}
