package dev.sam3vision.core.net

import dev.sam3vision.core.mask.base64Bytes
import dev.sam3vision.core.mask.base64Text
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Direct par WebSocket : une trame par frame sur `/live/ws` (JSON par défaut, binaire
 * M1 opt-in, POST /live en repli). La réponse porte le **même `id`**, corrélée par **file par
 * `id`** — le serveur peut répondre dans le désordre. Mesuré côté Modal : 50,3 ms en
 * WebSocket contre 81,4 ms en POST, soit ~31 ms gagnés par frame ; un `id` inconnu est
 * ignoré, et le health est délégué au client HTTP [http] (aucun `/health` dupliqué).
 *
 * Une coupure est un **état**, pas une frame perdue : [link] porte l'état observable
 * ([linkState]), le compteur de tentatives et le backoff plafonné ; [net] dit quand le
 * téléphone est hors ligne (échec local, sans handshake) ; [checkSilence] ferme un socket
 * muet et réveille les frames en vol.
 *
 * Le thread lecteur d'OkHttp ne **parse rien** : il lit l'`id` en tête ([readId]) et
 * transmet le texte brut ; le JSON n'est construit qu'une fois, chez l'appelant ([readJson]).
 */
class WsLiveClient(
    private val baseUrl: String,
    private val transport: WsTransport = OkHttpWsTransport(liveWsClient()),
    private var timeoutMs: Long = LIVE_TIMEOUT_SECONDS * 1000,
    private val http: LiveClient = HttpLiveClient(baseUrl),
    /** Jalons réseau pour la chronologie de l'écran (facultatif : les tests n'en posent pas). */
    private val marks: WireMarks? = null,
    /** Lecture de l'`id` en tête de message, **sans** JSON (injectée : les tests comptent). */
    private val readId: (String) -> Int = { leadingId(it) },
    /** Lecture du JSON d'une réponse, **une seule fois par trame** (injectée : les tests comptent). */
    private val readJson: (String) -> JSONObject? = { parse(it) },
    /** Format de masque demandé (`mask_format`) ; `null` = corps historique (`mask_encoding`). */
    private val maskFormat: String? = null,
    /** Côté long de la grille réduite (`mask_edge`) ; 0 = pleine résolution. */
    private val maskEdge: Int = 0,
    /** Profondeur fusionnée (`with_depth`) : médiane de proximité par objet en réponse. */
    private val withDepth: Boolean = false,
    /** Masques demandés (`mask_encoding`) ; `false` = boîtes et scores seuls (`none`). */
    private val masks: Boolean = true,
    /** Binaire M1 opt-in (`false` : l'ingress Modal tue l'opcode 0x2, 11/2026). */
    binaryWs: Boolean = false,
    /** Annonce du transport (`true` = binaire) : une fois à la première frame, puis au repli. */
    private val onBinaryChange: ((Boolean) -> Unit)? = null,
    /** Keepalive (FIX-17) : ping toutes les 30 s sans frame, support du watchdog. `null` = rien. */
    private val keepAlive: WsKeepAlive? = WsKeepAlive(),
    /** Réseau connu (défaut : on ne sait pas). `available=false` ⇒ échec local ; `iface` ⇒ invalide. */
    private val net: NetworkState = NetworkState.Always,
    /** Machine du lien (état, backoff, silence) : injectée pour piloter l'horloge en test. */
    private val link: WsLink = WsLink(),
    /** Repli HTTP collant + re-sonde WS (injecté : les tests règlent la sonde). */
    private val httpFallback: WsHttpFallback = WsHttpFallback(),
    /** Pools HTTP purgés quand le socket est jeté (injecté : les tests comptent). */
    private val onSocketDiscarded: () -> Unit = { drainBackendPools() },
) : LiveClient {

    private val lock = Any()

    /** Réponses attendues, indexées par `id` de requête. */
    private val pending = mutableMapOf<Int, PendingFrame>()
    private var nextId = 0
    private var connected = false
    /**
     * Génération du socket : chaque ouverture l'incrémente (sous [lock]). Un délai dépassé
     * ou une fermeture arrivant **après** une réouverture est périmé et ignoré — sinon le
     * timeout d'une frame tuait le socket rouvert par sa sœur.
     */
    private var socketGen = 0

    /** Horloges du lien (ping + watchdog) : un seul minuteur par session, rendu par [close]. */
    private var pump: WsLinkPump? = null

    /** Jambe WS : état + annonces ([WsLeg], verrou du client tenu par l'appelant). */
    private val wsLeg = WsLeg(binaryWs, onBinaryChange)

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val id = synchronized(lock) { ++nextId }
        val binary = synchronized(lock) { wsLeg.sendLeg() }
        // Message (~28 Ko) assemblé hors verrou : `onText` (même lock) ne doit pas attendre.
        val sender: () -> Boolean
        if (binary) {
            val jpeg = base64Bytes(jpegB64)
                ?: return Result.failure(ApiError.BadResponse(WS_BAD_IMAGE))
            val bytes = binaryFrame(id, jpeg, prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)
            sender = { transport.sendBytes(bytes) }
        } else {
            val message = frameMessage(id, jpegB64, prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)
            sender = { transport.send(message) }
        }
        val jsonSender: () -> Boolean =
            { transport.send(frameMessage(id, jpegB64, prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)) }
        return serve({ http.live(jpegB64, prompts, workEdge, maxObjects) }) { roundTrip(id, binary, sender, jsonSender) }
    }

    /** JPEG brut : trame M1 **sans base64**, envoyée d'un `sendBytes` avant l'attente. */
    override suspend fun liveBytes(
        jpeg: ByteArray,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        if (jpeg.isEmpty()) return Result.failure(ApiError.BadResponse(WS_BAD_IMAGE))
        val id = synchronized(lock) { ++nextId }
        val binary = synchronized(lock) { wsLeg.sendLeg() }
        val sender: () -> Boolean
        if (binary) {
            val bytes = binaryFrame(id, jpeg, prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)
            sender = { transport.sendBytes(bytes) }
        } else {
            val message = frameMessage(id, base64Text(jpeg), prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)
            sender = { transport.send(message) }
        }
        // Encodage paresseux : le repli JSON n'a lieu que sur `id: -1`, jamais avant.
        val jsonSender: () -> Boolean =
            { transport.send(frameMessage(id, base64Text(jpeg), prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)) }
        return serve({ http.liveBytes(jpeg, prompts, workEdge, maxObjects) }) { roundTrip(id, binary, sender, jsonSender) }
    }

    /** Un aller-retour avec repli : [jsonSender] rejoue en JSON quand le serveur répond `id: -1`. */
    private suspend fun roundTrip(
        id: Int,
        binary: Boolean,
        sender: () -> Boolean,
        jsonSender: () -> Boolean,
    ): Result<LiveFrame> = try {
        exchange(id, binary, sender).then { text -> parseReply(text, readJson) }
    } catch (e: FallbackToJson) {
        exchange(id, binary = false, jsonSender).then { text -> parseReply(text, readJson) }
    }

    /** Route d'une frame : POST direct si le repli colle, sinon WS avec repli en POST. */
    private suspend fun serve(httpCall: suspend () -> Result<LiveFrame>, wsCall: suspend () -> Result<LiveFrame>): Result<LiveFrame> {
        if (!synchronized(lock) { httpFallback.routeWs() }) {
            synchronized(lock) { if (!net.available) return Result.failure(ApiError.Network(WS_OFFLINE)) }
            return httpCall()
        }
        return try {
            wsCall().onSuccess { synchronized(lock) { httpFallback.onWsServed() } }
        } catch (e: FallbackToHttp) {
            // Hors ligne : le POST échouerait pareil — échec local, mais le repli colle.
            synchronized(lock) {
                httpFallback.onWsFailed()
                if (!net.available) return Result.failure(ApiError.Network(WS_OFFLINE))
            }
            httpCall()
        }
    }

    /** Un aller-retour sur le socket ; lève [FallbackToJson] sur `id: -1` (rejoué en texte). */
    private suspend fun exchange(id: Int, binary: Boolean, sender: () -> Boolean): Result<String> {
        // Porte réseau **avant** d'enregistrer l'attente : hors ligne connu ou backoff en
        // cours ⇒ refus local, aucun handshake, aucune entrée en vol à nettoyer.
        synchronized(lock) { admitLocked() }?.let { return Result.failure(it) }
        // Panne muette : sonde `/health` bornée au lieu d'une pleine frame (WsLink).
        if (!link.mutedProbe(http)) return Result.failure(ApiError.Network(WS_RECONNECTING))
        val reply = CompletableDeferred<String>()
        val opened = synchronized(lock) {
            // L'attente est enregistrée **avant** l'ouverture : une connexion refusée peut
            // survenir immédiatement, elle doit réveiller la frame.
            pending[id] = PendingFrame(reply, binary)
            if (openLocked()) true else {
                pending.remove(id)
                false
            }
        }
        // Ouverture impossible : la frame part en POST, sans attendre le WS.
        if (!opened) throw FallbackToHttp
        // Génération servie par cette frame : si le socket est refermé puis rouvert pendant
        // son attente, son propre délai ne tuera pas le socket neuf.
        val gen = synchronized(lock) { socketGen }
        // Envoi **hors verrou** : le thread lecteur (`onText`, même verrou) continue de
        // réveiller les frames en vol. Un refus d'envoi **invalide** le socket : il est mort.
        if (!sender()) {
            synchronized(lock) { pending.remove(id) }
            invalidate(WS_SEND_REFUSED)
            // Envoi refusé : socket mort, la frame part en POST.
            throw FallbackToHttp
        }
        // La frame prouve que le socket vit : le prochain ping recule d'un intervalle.
        keepAlive?.markActive()
        return try {
            awaitReply(id, reply, gen)
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    /** Vrai tant que le socket est ouvert — l'UI s'en sert pour dire « WebSocket » ou « KO ». */
    val isConnected: Boolean get() = synchronized(lock) { connected }

    /** Vrai quand les frames partent en POST /live — l'UI dit « HTTP » au lieu de « KO ». */
    val httpFallbackActive: Boolean get() = synchronized(lock) { httpFallback.active }
    /** Jambe empruntée par les frames ([legOf]) : lue par la sonde à chaque publication. */
    val leg: String get() = synchronized(lock) { legOf(httpFallback.active, wsLeg.isBinary) }

    /**
     * État du lien, **observable** : `Open` = socket vivant, `Degraded` = coupure connue et
     * reprise armée (l'écran dit « reconnexion »), `Closed` = après [close].
     */
    val linkState: StateFlow<WsLinkState> get() = link.state
    val linkSnapshot: LinkSnapshot get() = synchronized(lock) { link.snapshot() }

    /**
     * Le lien n'est plus celui qu'on croyait : réseau changé, socket déclaré mort par le
     * watchdog, envoi refusé. Ferme le socket, réveille **toutes** les frames en vol en
     * échec (au lieu de leur faire attendre leur budget) et arme une reprise (immédiate
     * au premier, backoff sinon). Appelable de n'importe quel thread : tout passe par
     * le verrou du client.
     */
    fun invalidate(reason: String) {
        synchronized(lock) {
            if (!connected && pending.isEmpty()) return
            link.onInvalidate(net.iface)
            dropSocketLocked(ApiError.Network(reason))
        }
        runCatching { transport.close() }
    }

    /**
     * Tick de surveillance : plus aucune réponse ni preuve de vie depuis le silence toléré
     * ⇒ socket zombie (conteneur arrêté, radio perdue sans FIN). On le ferme ici plutôt
     * que de laisser la frame suivante payer son budget devant un écran figé.
     */
    internal fun checkSilence() {
        val silent = synchronized(lock) {
            connected && link.silentForMs() > link.silenceBudgetMs(timeoutMs)
        }
        if (silent) invalidate(WS_SILENT)
    }

    /** Handshake anticipé, idempotent ; `false` hors ligne ou pendant un backoff. */
    override fun preconnect(): Boolean = synchronized(lock) { openLocked() }

    /**
     * Bascule chaud (FIX-18) : le budget froid (60 s) retombe au budget normal, sur le
     * socket ouvert — la prochaine frame attend juste moins longtemps, aucun handshake.
     */
    fun useTimeoutMs(ms: Long) {
        synchronized(lock) { timeoutMs = ms }
    }

    /** Budget courant, exposé aux tests : la preuve de la décroissance froid → normal. */
    internal val currentTimeoutMs: Long get() = synchronized(lock) { timeoutMs }

    /** Jambe imposée par le bit `ws_binary` du `/health` (FIX-4). `false` = JSON direct. */
    fun useBinaryWs(binary: Boolean) {
        synchronized(lock) { wsLeg.use(binary) }
    }

    /** Le WebSocket ne sert pas au health : un seul appel `/health`, celui du client HTTP. */
    override suspend fun health(): Result<LiveHealth> = http.health()

    override fun close() {
        // Marqué d'abord : une fermeture tardive de l'ancien socket (ack du `close`)
        // arrive après, périmée, et ne touche plus rien.
        synchronized(lock) {
            connected = false
            socketGen++
            stopPingLocked()
            // État remis à zéro : ni « reconnexion » fantôme, ni backoff, ni repli HTTP après fermeture.
            link.reset()
            httpFallback.reset()
        }
        transport.close()
        failPending(ApiError.Network(WS_CLOSED))
    }

    /** Démarre les horloges du lien (appel sous [lock], depuis [openLocked]) : un minuteur. */
    private fun startPingLocked() {
        val keep = keepAlive ?: return
        if (pump != null) return
        pump = WsLinkPump(
            keep,
            isOpen = { synchronized(lock) { connected } },
            inFlight = { synchronized(lock) { pending.isNotEmpty() } },
            send = { message -> runCatching { transport.send(message) }.getOrDefault(false) },
            onTick = ::checkSilence,
        ).also { it.start() }
    }

    /** Arrêt des horloges (appel sous [lock]) : socket fermé, plus rien à tenir éveillé. */
    private fun stopPingLocked() {
        pump?.stop()
        pump = null
    }

    /** Pompe en route, exposée aux tests : le câblage minuteur ↔ socket. */
    internal val pingActive: Boolean get() = synchronized(lock) { pump?.running == true }

    /** Ouvre la connexion si besoin (appel sous [lock]) ; `false` = rien à ouvrir. */
    private fun openLocked(): Boolean {
        if (connected) return true
        // Hors ligne connu : aucune tentative, donc aucun handshake dans le vide.
        if (!net.available) {
            link.onOffline()
            return false
        }
        // Backoff en cours : une seule tentative par fenêtre, jamais de rafale.
        if (!link.canAttempt()) return false
        val gen = socketGen + 1
        link.startConnecting()
        // Posés AVANT l'appel : un `101` synchrone doit trouver son socket.
        socketGen = gen
        connected = true
        val opened = transport.open(
            webSocketUrl(baseUrl, LIVE_WS_PATH),
            { text -> onText(text) },
            { error -> onClosed(error, gen) },
            // `101` : socket ouvert, compteur intact (raz à la 1ʳᵉ réponse seulement).
            // Le remettre ici rebattait à zéro face à un serveur muet, en boucle.
            { onSocketOpen(gen) },
        )
        if (!opened) {
            connected = false
            link.onFailure()
            return false
        }
        startPingLocked()
        return true
    }

    /**
     * Porte d'entrée d'une frame (sous [lock]), calculée **avant** tout enregistrement :
     * endpoint inutilisable ⇒ refus local actionnable (Réglages), sans handshake ;
     * hors ligne ⇒ refus local (ni DNS, ni TCP, ni handshake) ; interface changée ⇒ socket
     * mort-né jeté ici, mais **la frame courante repart** sur la nouvelle interface (une
     * bascule Wi-Fi → 4G ne doit pas coûter une frame : elle coûte un handshake) ;
     * backoff en cours ⇒ refus immédiat, sans consommer d'ouverture.
     */
    private fun admitLocked(): ApiError? {
        endpointRefusal("endpoint direct", baseUrl, LIVE_WS_PATH)?.let { return it }
        if (!net.available) {
            link.onOffline()
            return discardSocketLocked(WS_OFFLINE)
        }
        if (link.ifaceChanged(net.iface)) {
            // Jette le socket de l'ancienne interface et réveille les frames qui l'attendaient ;
            // celle-ci n'est pas encore enregistrée, elle rouvrira juste après.
            discardSocketLocked(WS_IFACE_CHANGED)
            return null
        }
        if (!connected && !link.canAttempt()) return ApiError.Network(WS_RECONNECTING)
        return null
    }

    /** Hors ligne / réseau changé : socket mort-né jeté, pools HTTP purgés avec. */
    private fun discardSocketLocked(reason: String): ApiError {
        dropSocketLocked(ApiError.Network(reason))
        runCatching { transport.close() }
        runCatching { onSocketDiscarded() }
        return ApiError.Network(reason)
    }

    /** Ferme le socket (sous [lock]) : génération neuve, horloges arrêtées, frames réveillées. */
    private fun dropSocketLocked(error: ApiError) {
        connected = false
        socketGen++
        stopPingLocked()
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.reply.completeExceptionally(error) }
    }

    /** Message entrant (thread lecteur) : seule la requête portant cet `id` est réveillée. */
    private fun onText(text: String) {
        val id = readId(text)
        val reply = synchronized(lock) {
            // Toute réponse prouve que le socket vit (le watchdog recule) ; une
            // réponse corrélée prouve en plus qu'il sert (le timeout épargne).
            val hit = pending[id]?.reply
            if (hit == null) {
                link.markProof()
                noteBinaryRefused(id, text)
            } else {
                link.markFrameProof()
            }
            hit
        } ?: return
        // Corps (message) complet, avant le parsing de la réponse par l'appelant.
        marks?.onBody()
        reply.complete(text)
    }

    /** Orphelin `{"id":-1,"error":…}` : serveur sans WS binaire → repli JSON (sous [lock]). */
    private fun noteBinaryRefused(id: Int, text: String) {
        if (id != UNKNOWN_WS_ID || ERROR_MARK !in text) return
        wsLeg.refuse()
        pending.values.firstOrNull { it.binary }?.reply?.completeExceptionally(FallbackToJson)
    }

    /**
     * Socket fermé : les frames en vol échouent, l'état passe en `Degraded` et la reprise
     * est armée — sauf si la fermeture vient d'un socket déjà remplacé (ack tardif).
     * Idle serveur (`1001`) : reprise **sans délai au premier** (contrat LIVE-SPEC §9),
     * puis backoff si les idles se répètent sans preuve — un idle compte, sans attendre.
     * [onText] reste sans garde : une réponse tardive au bon `id` reste la bonne.
     */
    private fun onClosed(error: Throwable?, gen: Int) {
        val stale = synchronized(lock) {
            if (gen != socketGen) {
                true
            } else {
                connected = false
                stopPingLocked()
                if (error is WsClosedAbnormally && error.code == IDLE_CLOSE_CODE) {
                    link.onInvalidate(net.iface, closeCodeOf(error))
                } else {
                    link.onFailure(closeCodeOf(error))
                }
                false
            }
        }
        if (stale) return
        // Jambe tuée sans réponse (ingress Modal 11/2026 : close 1000 propre dès
        // l'envoi binaire) : les frames binaires en vol rejouent en JSON et la
        // jambe texte colle — sinon chaque frame suivante se fait re-tuer pareil.
        val killedBinary = synchronized(lock) {
            val unexpected = link.current != WsLinkState.Closed &&
                !(error is WsClosedAbnormally && error.code == IDLE_CLOSE_CODE)
            val kill = unexpected && wsLeg.isBinary && pending.values.any { it.binary }
            if (kill) wsLeg.refuse()
            kill
        }
        if (killedBinary) {
            val replayed = synchronized(lock) {
                pending.filterValues { it.binary }.keys.toList().mapNotNull { pending.remove(it) }
            }
            replayed.forEach { it.reply.completeExceptionally(FallbackToJson) }
        }
        failPending(error?.let(::wsCloseError) ?: ApiError.Network(WS_CLOSED))
    }

    /** `101` de [gen] : socket ouvert — le compteur survit (raz à la 1ʳᵉ réponse). */
    private fun onSocketOpen(gen: Int) {
        synchronized(lock) {
            if (connected && gen == socketGen) link.onOpen(net.iface)
        }
    }

    private fun failPending(failure: Throwable) {
        val waiting = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { frame -> frame.reply.completeExceptionally(failure) }
    }

    private suspend fun awaitReply(id: Int, reply: CompletableDeferred<String>, gen: Int): Result<String> {
        // Budget lu à chaque frame : la bascule chaud (FIX-18) le fait retomber sans rouvrir.
        val budget = synchronized(lock) { timeoutMs }
        return try {
            val raw = withTimeoutOrNull(budget) { reply.await() }
            if (raw == null) {
                // Le serveur annule l'inférence abandonnée (one-way, sans réponse).
                runCatching { transport.send(cancelMessage(id)) }
                dropDeadSocket(gen, id)
                Result.failure(ApiError.Timeout())
            } else {
                Result.success(raw)
            }
        } catch (e: ApiError) {
            // Socket tombé en vol : la frame repart en POST — sauf session fermée et sauf délai.
            if (e is ApiError.Network && synchronized(lock) { link.current } != WsLinkState.Closed) throw FallbackToHttp
            Result.failure(e)
        }
    }

    /**
     * Délai dépassé : si une frame a été répondue dans le budget, le socket sert
     * et survit avec ses sœurs (lenteur, pas mort — seul ce timeout échoue).
     * Sinon socket muet : coupe via [dropSocketLocked] (génération neuve, donc
     * l'ack du `close` est toujours périmé), sœurs en `Timeout`, et **réouverture
     * aussitôt** (FIX-16) — sauf [gen] périmé.
     */
    private fun dropDeadSocket(gen: Int, id: Int) {
        synchronized(lock) {
            if (!(connected && socketGen == gen)) return
            if (link.frameProvenWithin(timeoutMs)) return
            link.onFailure()
            pending.remove(id)
            dropSocketLocked(ApiError.Timeout())
        }
        runCatching { transport.close() }
        synchronized(lock) { openLocked() }
    }
}
