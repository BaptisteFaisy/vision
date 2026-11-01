package dev.sam3vision.core.net

import dev.sam3vision.core.mask.base64Bytes
import dev.sam3vision.core.mask.base64Text
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Direct par WebSocket : une trame par frame sur `/live/ws` (binaire M1 par défaut,
 * JSON en repli), réponse portant le **même `id`**. Mesuré côté Modal : 50,3 ms en WebSocket contre 81,4 ms en POST, soit
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
    /**
     * Envoi binaire M1 : JPEG brut (longueur + en-tête + JPEG) au lieu du JSON base64
     * (−33 % à la montée, plus de base64 à l'aller). `true` par défaut : le serveur
     * déployé lit le binaire. Quand le serveur répond `{"id":-1,"error":…}` (vieux
     * serveur, ou trame rejetée), la frame repart une fois en JSON et les suivantes
     * partent directement en texte.
     */
    private val binaryWs: Boolean = true,
    /**
     * Annonce du transport (`true` = binaire) : une fois à la première frame, puis au
     * repli texte. `null` = silencieuse. Comme [WireMarks], non bloquante et sans état
     * lourd (appelée sous [lock], sur le thread d'envoi ou le lecteur).
     */
    private val onBinaryChange: ((Boolean) -> Unit)? = null,
) : LiveClient {

    private val lock = Any()

    /** Réponses attendues, indexées par `id` de requête. */
    private val pending = mutableMapOf<Int, PendingFrame>()
    private var nextId = 0
    private var connected = false

    /**
     * Le serveur a déjà répondu `id: -1` : sans WS binaire, les frames suivantes partent
     * directement en JSON (repli collant, un seul aller-retour perdu par session).
     */
    private var binaryRefused = false

    /** Le transport initial a déjà été annoncé à [onBinaryChange] (une fois par session). */
    private var legNoted = false

    /** Une frame en vol : son réveil, et si elle est partie en binaire (repli JSON). */
    private class PendingFrame(val reply: CompletableDeferred<String>, val binary: Boolean)

    /** Signal interne : `id: -1` reçu — la frame binaire en vol repart en JSON. */
    private object FallbackToJson : Exception("WS binaire refusé (id -1) : repli JSON")

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val id = synchronized(lock) { ++nextId }
        val binary = synchronized(lock) { sendLegLocked() }
        // Message (~28 Ko) assemblé hors verrou : `onText` (même lock) ne doit pas attendre.
        val sender: () -> Boolean
        if (binary) {
            val jpeg = base64Bytes(jpegB64)
                ?: return Result.failure(ApiError.BadResponse(WS_BAD_IMAGE))
            val bytes = binaryFrame(id, jpeg, prompts, workEdge, maxObjects)
            sender = { transport.sendBytes(bytes) }
        } else {
            val message = frameMessage(id, jpegB64, prompts, workEdge, maxObjects)
            sender = { transport.send(message) }
        }
        val jsonSender: () -> Boolean =
            { transport.send(frameMessage(id, jpegB64, prompts, workEdge, maxObjects)) }
        return roundTrip(id, binary, sender, jsonSender)
    }

    /**
     * JPEG brut : la trame M1 est assemblée **sans base64** (ni `String` +33 %, ni
     * décodage) et envoyée d'un seul `sendBytes`, synchrone avant l'attente de la
     * réponse — aucun tampon applicatif entre l'encodeur et le socket. Le base64
     * n'est construit qu'en repli (serveur sans binaire : une frame, puis collant).
     */
    override suspend fun liveBytes(
        jpeg: ByteArray,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        if (jpeg.isEmpty()) return Result.failure(ApiError.BadResponse(WS_BAD_IMAGE))
        val id = synchronized(lock) { ++nextId }
        val binary = synchronized(lock) { sendLegLocked() }
        val sender: () -> Boolean
        if (binary) {
            val bytes = binaryFrame(id, jpeg, prompts, workEdge, maxObjects)
            sender = { transport.sendBytes(bytes) }
        } else {
            val message = frameMessage(id, base64Text(jpeg), prompts, workEdge, maxObjects)
            sender = { transport.send(message) }
        }
        // Encodage paresseux : le repli JSON n'a lieu que sur `id: -1`, jamais avant.
        val jsonSender: () -> Boolean =
            { transport.send(frameMessage(id, base64Text(jpeg), prompts, workEdge, maxObjects)) }
        return roundTrip(id, binary, sender, jsonSender)
    }

    /**
     * Un aller-retour avec repli : [sender] porte l'encodage (binaire ou texte),
     * [jsonSender] le renvoi en JSON quand le serveur répond `id: -1` (vieux serveur).
     */
    private suspend fun roundTrip(
        id: Int,
        binary: Boolean,
        sender: () -> Boolean,
        jsonSender: () -> Boolean,
    ): Result<LiveFrame> {
        return try {
            exchange(id, binary, sender).then { text -> parseReply(text) }
        } catch (e: FallbackToJson) {
            exchange(id, binary = false, jsonSender).then { text -> parseReply(text) }
        }
    }

    /**
     * Un aller-retour sur le socket (ouvert ou à ouvrir) : [sender] porte l'encodage
     * (texte ou binaire M1), `binary` dit si la frame peut être rappelée en JSON. Lève
     * [FallbackToJson] quand le serveur répond `id: -1` (l'appelant renvoie en texte).
     */
    private suspend fun exchange(id: Int, binary: Boolean, sender: () -> Boolean): Result<String> {
        val reply = CompletableDeferred<String>()
        val opened = synchronized(lock) {
            // L'attente est enregistrée **avant** l'ouverture : un échec de socket
            // (connexion refusée) peut survenir immédiatement, il doit réveiller la frame.
            pending[id] = PendingFrame(reply, binary)
            if (!openLocked()) {
                pending.remove(id)
                false
            } else {
                true
            }
        }
        if (!opened) return Result.failure(ApiError.Network(WS_UNREACHABLE))
        // Envoi **hors verrou** : pendant que la frame part sur le socket, le thread
        // lecteur (`onText`, même verrou) continue de réveiller les frames en vol —
        // sinon un envoi lent figeait toutes les réceptions. Si le socket meurt entre
        // l'ouverture et l'envoi, `send` refuse (ou `onClosed` a déjà réveillé la
        // frame en échec) : les deux chemins rendent un échec réseau, jamais un succès.
        if (!sender()) {
            synchronized(lock) { pending.remove(id) }
            return Result.failure(ApiError.Network("WebSocket /live/ws: send refused"))
        }
        return try {
            awaitReply(reply)
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    /** Vrai tant que le socket est ouvert — l'UI s'en sert pour dire « WebSocket » ou « KO ». */
    val isConnected: Boolean get() = synchronized(lock) { connected }

    /**
     * Handshake anticipé : ouvre le socket sans envoyer, pour que la première frame parte
     * sur un transport déjà prêt. Idempotent ([openLocked] ne rouvre jamais un socket
     * ouvert) et sans attente : le handshake se fait en fond, l'appelant continue.
     */
    override fun preconnect(): Boolean = synchronized(lock) { openLocked() }

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

    /** Trame M1 : les knobs du client appliqués à [frameMessageBinary]. */
    private fun binaryFrame(
        id: Int,
        jpeg: ByteArray,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): ByteArray = frameMessageBinary(id, jpeg, prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)

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
        val id = readId(text)
        val reply = synchronized(lock) {
            pending[id]?.reply ?: run {
                noteBinaryRefused(id, text)
                null
            }
        } ?: return
        // Corps (message) complet, avant le parsing de la réponse par l'appelant : c'est le
        // poste « corps » de la chronologie. Un message orphelin ne date rien.
        marks?.onBody()
        reply.complete(text)
    }

    /**
     * Transport de la frame qui part, avec l'annonce initiale à [onBinaryChange] (appel
     * sous [lock], depuis `live`/`liveBytes` : un seul passage annonce, les deux chemins
     * partagent le choix).
     */
    private fun sendLegLocked(): Boolean {
        val binary = binaryWs && !binaryRefused
        if (!legNoted) {
            legNoted = true
            onBinaryChange?.invoke(binary)
        }
        return binary
    }

    /**
     * Orphelin `{"id":-1,"error":…}` : signature d'un serveur sans WS binaire (il ne lit
     * que du texte). La plus ancienne frame binaire en vol est réveillée en repli JSON,
     * les suivantes partent directement en texte. Sans construction de JSON : le marqueur
     * `"error"` suffit, une trame orpheline ne coûte toujours rien (appel sous [lock]).
     */
    private fun noteBinaryRefused(id: Int, text: String) {
        if (!binaryWs || id != UNKNOWN_WS_ID || ERROR_MARK !in text) return
        if (!binaryRefused) {
            binaryRefused = true
            onBinaryChange?.invoke(false)
        }
        pending.values.firstOrNull { it.binary }?.reply?.completeExceptionally(FallbackToJson)
    }

    /** Socket fermé ou en panne : les frames en vol échouent tout de suite. */
    private fun onClosed(error: Throwable?) {
        synchronized(lock) { connected = false }
        failPending(error?.toApiError() ?: ApiError.Network(WS_CLOSED))
    }

    private fun failPending(failure: Throwable) {
        val waiting = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { frame -> frame.reply.completeExceptionally(failure) }
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
        const val WS_BAD_IMAGE = "WebSocket /live/ws : image_b64 illisible"
        const val UNKNOWN_WS_ID = -1
        const val ERROR_MARK = "\"error\""
    }
}
