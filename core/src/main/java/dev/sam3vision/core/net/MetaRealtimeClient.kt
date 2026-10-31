package dev.sam3vision.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Dictée temps réel Meta (`muse-voice-transcribe-1.0`) par WebSocket.
 *
 * Le batch (`MetaAsrClient`) envoie le WAV complet au relâchement puis attend
 * l'inférence sur tout l'énoncé : ici le PCM part **pendant** la tenue (trames
 * binaires de PCM16 16 kHz brut — exactement ce que le micro produit), le
 * serveur répond des partiels cumulatifs, et au relâchement `endStream` fige
 * le tour en un transcript final. Le relâchement ne coûte alors que la queue
 * audio + un aller-retour, au lieu d'un envoi complet + une inférence complète.
 *
 * Protocole : `wss://{base}/asr/realtime`, mode `PUSH_TO_TALK` (le doigt
 * délimite le tour), recette officielle `meta-model-cookbook`
 * (`06_muse_voice/01_voice_api_fundamentals`, relue le 2026-10-31) :
 * poignée JSON avec la clé dedans (`authorization.accessToken`, l'en-tête
 * `Authorization` est ignoré), `audioEncoding: PCM_16KHZ` (16 kHz accepté,
 * rééchantillonné côté serveur — le micro ne convertit rien),
 * `partialMode: CUMULATIVE`, trames 80 ms, fin par `{"type":"endStream"}`.
 *
 * Coupures serveur (README recette) : pas de poignée en 10 s, arriéré audio
 * > 5 s, ingress sous le temps réel 10 s (1008) ; session > 60 min (1011) ;
 * quota (1013). D'où : poignée envoyée dès l'ouverture, tampon pré-ack borné
 * à 4 s ([PENDING_CAP_BYTES]), micro continu (silences compris, jamais de
 * trou), session d'une tenue (secondes).
 *
 * Le parsing se fait sur le thread lecteur d'OkHttp : les trames font
 * ~100 octets à ~2 Ko à ~12 trames/s, pas ~28 Ko à 30 ips comme le direct —
 * la règle « zéro JSON sur le lecteur » de [WsLiveClient] ne s'y applique pas.
 */
interface MuseStreamSession {
    /** Ouvre le socket, envoie la poignée, attend l'ack ([Result.failure] sinon). */
    suspend fun start(): Result<Unit>

    /** Enfile [count] octets de PCM16 16 kHz ; `false` = session morte. */
    fun sendPcm(chunk: ByteArray, count: Int = chunk.size): Boolean

    /**
     * Envoie la queue, puis `endStream`, attend le transcript final et ferme.
     * Texte brut (le cas d'usage décide quoi faire d'un texte vide, comme
     * pour le batch).
     */
    suspend fun endStreamAndFinal(): Result<String>

    /** Ferme sans `endStream` (annulation, repli batch, tenue trop courte). */
    fun abort()

    /** Vrai entre l'ack et la mort/fermeture du socket. */
    val isLive: Boolean
}

/** Une session de dictée temps réel Muse : ouvrir, streamer, finir, fermer. */
class MetaRealtimeSession(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val transport: WsTransport = OkHttpWsTransport(museWsClient()),
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val finalTimeoutMs: Long = FINAL_TIMEOUT_MS,
    /** Partiel cumulatif (thread lecteur) : l'appelant filtre (tenue, écran). */
    private val onPartial: (String) -> Unit = {},
    /** Session morte **après** l'ack : l'appelant dégrade (batch). */
    private val onError: (ApiError) -> Unit = {},
) : MuseStreamSession {

    private val lock = Any()
    private val acked = CompletableDeferred<Unit>()
    private val finalized = CompletableDeferred<String>()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var streamedBytes = 0L
    private var live = false
    private var born = false
    private var done = false
    private var dead = false

    override val isLive: Boolean get() = synchronized(lock) { live && !dead && !done }

    override suspend fun start(): Result<Unit> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("clé Meta absente — renseigne-la dans Réglages"))
        }
        endpointRefusal("endpoint Meta", baseUrl, REALTIME_PATH)?.let { return Result.failure(it) }
        val opened = synchronized(lock) {
            if (dead) return Result.failure(ApiError.Network(WS_UNREACHABLE))
            transport.open(
                museRealtimeUrl(baseUrl),
                { text -> onText(text) },
                { error -> onClosed(error) },
            ) && transport.send(museHandshake(apiKey, model))
        }
        if (!opened) {
            synchronized(lock) { dead = true }
            return Result.failure(ApiError.Network(WS_UNREACHABLE))
        }
        // Tri-état : ack, mort avec sa cause, ou vrai silence (délai).
        val outcome: Result<Unit>? = withTimeoutOrNull(startTimeoutMs) {
            try {
                acked.await()
                Result.success(Unit)
            } catch (e: ApiError) {
                Result.failure(e)
            }
        }
        if (outcome == null) {
            synchronized(lock) { dead = true }
            runCatching { transport.close() }
            return Result.failure(ApiError.Timeout("Muse realtime : session non démarrée"))
        }
        return outcome
    }

    override fun sendPcm(chunk: ByteArray, count: Int): Boolean {
        require(count in 0..chunk.size)
        // Copie : le tampon du micro est réutilisé d'une lecture à l'autre.
        val copy = if (count == 0) null else chunk.copyOf(count)
        synchronized(lock) {
            if (dead || done) return false
            if (copy == null) return true
            if (pendingBytes + count > PENDING_CAP_BYTES) {
                die(ApiError.BadResponse("Muse realtime : tampon plein, session non démarrée"))
                return false
            }
            pending.addLast(copy)
            pendingBytes += count
            streamedBytes += count
            if (!live) return true
            return flushLocked()
        }
    }

    override suspend fun endStreamAndFinal(): Result<String> {
        val tail: ByteArray = synchronized(lock) {
            if (dead) return fail(closedError())
            if (streamedBytes == 0L) {
                done = true
                runCatching { transport.close() }
                return Result.failure(ApiError.BadResponse("audio vide"))
            }
            takeLocked(pendingBytes)
        }
        val sent = synchronized(lock) {
            if (dead) return fail(closedError())
            // Reliquat vide (multiple exact de trame) : `endStream` seul, jamais
            // une trame binaire vide.
            (tail.isEmpty() || transport.sendBytes(tail)) && transport.send(END_STREAM)
        }
        if (!sent) return fail(ApiError.Network("Muse realtime : envoi refusé"))
        // Pas de `return` dans le bloc (`withTimeoutOrNull` n'est pas inline) :
        // la cause remonte par variable, puis échoue hors du délai.
        var failure: ApiError? = null
        val text = withTimeoutOrNull(finalTimeoutMs) {
            try {
                finalized.await()
            } catch (e: ApiError) {
                failure = e
                null
            }
        }
        failure?.let { return fail(it) }
        synchronized(lock) { done = true }
        runCatching { transport.close() }
        if (text == null) return fail(ApiError.Timeout("Muse realtime : fin de tour sans réponse"))
        return Result.success(text)
    }

    override fun abort() {
        synchronized(lock) {
            done = true
            dead = true
        }
        failPending(ApiError.Network(WS_CLOSED))
        runCatching { transport.close() }
    }

    /** Trames pleines de 80 ms ; le reliquat part avec `endStream`. */
    private fun flushLocked(): Boolean {
        while (pendingBytes >= FRAME_BYTES) {
            val frame = takeLocked(FRAME_BYTES)
            if (!transport.sendBytes(frame)) {
                die(ApiError.Network("Muse realtime : envoi refusé"))
                return false
            }
        }
        return true
    }

    /** Prend [n] octets en tête du tampon (appel sous [lock]). */
    private fun takeLocked(n: Int): ByteArray {
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val head = pending.removeFirst()
            val rest = n - filled
            if (head.size <= rest) {
                head.copyInto(out, filled)
                filled += head.size
            } else {
                head.copyInto(out, filled, 0, rest)
                pending.addFirst(head.copyOfRange(rest, head.size))
                filled = n
            }
        }
        pendingBytes -= n
        return out
    }

    /**
     * Première mort signalée : une erreur trame + la fermeture qui suit = un
     * seul appel, **hors verrou** (le rappel appartient à l'appelant).
     */
    private fun die(cause: ApiError) {
        val notify = synchronized(lock) {
            if (dead) {
                false
            } else {
                dead = true
                live = false
                failPending(cause)
                born && !done
            }
        }
        if (notify) onError(cause)
    }

    private fun failPending(cause: ApiError) {
        acked.completeExceptionally(cause)
        finalized.completeExceptionally(cause)
    }

    private fun fail(cause: ApiError): Result<String> {
        die(cause)
        return Result.failure(cause)
    }

    private fun closedError(): ApiError =
        ApiError.Network("Muse realtime : session fermée avant la fin du tour")

    /** Message entrant (thread lecteur) : petit, parsé ici (voir KDoc). */
    private fun onText(text: String) {
        // Première trame : ack de poignée — erreur typée, sinon la session vit
        // (la recette accepte toute première trame non-erreur, `sessionId` nu
        // compris).
        val first = synchronized(lock) { !born && !dead && !done }
        if (first) {
            onAck(text)
            return
        }
        when (val event = parseMuseEvent(text)) {
            null, MuseEvent.Ignored -> Unit
            is MuseEvent.Partial -> onPartial(event.text)
            is MuseEvent.Committed -> onCommitted(event.text)
            is MuseEvent.Failed -> die(event.error)
            MuseEvent.Started -> Unit
        }
    }

    private fun onAck(text: String) {
        val event = parseMuseEvent(text)
        if (event is MuseEvent.Failed) {
            die(event.error)
            return
        }
        synchronized(lock) {
            if (dead || done) return
            live = true
            born = true
            flushLocked()
        }
        acked.complete(Unit)
    }

    private fun onCommitted(text: String) {
        synchronized(lock) {
            if (dead || done) return
            done = true
        }
        finalized.complete(text)
    }

    private fun onClosed(error: Throwable?) {
        // `done` est posé sous le même verrou par [onCommitted] : un final
        // reçu juste avant la fermeture reste un succès, jamais une erreur.
        val cause = synchronized(lock) {
            if (done) {
                null
            } else {
                when (error) {
                    null -> ApiError.Network(WS_CLOSED_CLEAN)
                    is WsClosedAbnormally -> closeError(error)
                    else -> error.toApiError()
                }
            }
        } ?: return
        die(cause)
    }

    /** Coupures documentées (README recette) : quota typé, le reste réseau. */
    private fun closeError(closed: WsClosedAbnormally): ApiError = when (closed.code) {
        1013 -> ApiError.RateLimited("Muse realtime : quota atteint (1013), réessaie dans un moment")
        1008 -> ApiError.Network("Muse realtime : cadence refusée (1008)")
        1011 -> ApiError.Network("Muse realtime : serveur ou durée max (1011)")
        else -> ApiError.Network("Muse realtime : fermeture ${closed.code}")
    }

    companion object {
        const val DEFAULT_MODEL = "muse-voice-transcribe-1.0"
        const val REALTIME_PATH = "/asr/realtime"

        /** Poignée + ack : le serveur répond à la poignée en premier, vite. */
        const val START_TIMEOUT_MS = 10_000L

        /** Fin de tour : queue audio + inférence du tour. */
        const val FINAL_TIMEOUT_MS = 15_000L

        /** 80 ms de PCM16 16 kHz mono par trame (cadence de la recette). */
        const val FRAME_BYTES = 2_560

        /**
         * ~4 s d'audio en attente de l'ack, jamais plus : au-delà de 5 s
         * d'arriéré le serveur coupe (1008).
         */
        const val PENDING_CAP_BYTES = 128_000

        /** Demi-fermeture de l'entrée audio, le socket restant ouvert. */
        const val END_STREAM = """{"type":"endStream"}"""

        const val WS_UNREACHABLE = "Muse realtime injoignable"
        const val WS_CLOSED = "Muse realtime : session fermée"
        const val WS_CLOSED_CLEAN = "Muse realtime : session fermée sans final"
    }
}

/** Événement Muse parsé : seuls `Partial`/`Committed`/`Failed` agissent. */
internal sealed interface MuseEvent {
    data object Started : MuseEvent
    data class Partial(val text: String) : MuseEvent
    data class Committed(val text: String) : MuseEvent
    data class Failed(val error: ApiError) : MuseEvent
    data object Ignored : MuseEvent
}

/** `wss://` + chemin realtime sur la base ASR des réglages (pont local OK). */
internal fun museRealtimeUrl(baseUrl: String): String =
    webSocketUrl(baseUrl, MetaRealtimeSession.REALTIME_PATH)

/**
 * Poignée d'ouverture (recette officielle) : mode push-to-talk, clé **dans**
 * le JSON (`authorization.accessToken`, l'en-tête est ignoré), PCM 16 kHz,
 * partiels cumulatifs. Clé et modèle échappés (saisis par l'utilisateur).
 */
internal fun museHandshake(apiKey: String, model: String): String =
    """{"mode":"PUSH_TO_TALK","authorization":{"accessToken":"${jsonEscape(apiKey)}"},""" +
        """"audioEncoding":"PCM_16KHZ","model":"${jsonEscape(model)}",""" +
        """"partialMode":"CUMULATIVE","emitAudioProgress":false}"""

/**
 * Réponse Muse : `sessionId` nu (ack), `transcript` (partiel cumulatif, ou
 * final si `final:true`), `speechComplete` (final défensif), `error` typée
 * par mots-clés du message (le serveur n'envoie pas de code). `speaker`,
 * `speechStart`/`speechEnd`, inconnus : ignorés. JSON illisible ignoré
 * (le socket parle encore, la session vit).
 */
internal fun parseMuseEvent(raw: String): MuseEvent? {
    val json: JSONObject = parse(raw) ?: return null
    if (!json.has("type")) {
        return if (json.has("sessionId")) MuseEvent.Started else MuseEvent.Ignored
    }
    return when (json.str("type")) {
        "transcript" -> {
            if (!json.has("final")) return MuseEvent.Ignored
            val text = json.str("transcript")
            if (json.optBoolean("final", false)) MuseEvent.Committed(text) else MuseEvent.Partial(text)
        }

        "speechComplete" -> MuseEvent.Committed(json.str("transcript"))
        "error" -> MuseEvent.Failed(mapMuseError(json.str("message")))
        else -> MuseEvent.Ignored
    }
}

/**
 * Erreur serveur sans code : typée par mots-clés (barème du batch). Ordre :
 * auth, facturation, quota, puis générique — un message inconnu reste une
 * `BadResponse` nommée, jamais silencieuse.
 */
internal fun mapMuseError(message: String): ApiError {
    val lower = message.lowercase()
    val detail = message.take(120)
    return when {
        listOf("auth", "token", "credential", "unauthorized", "forbidden", "401", "403")
            .any { lower.contains(it) } ->
            ApiError.InvalidKey("clé Meta invalide (realtime)")

        listOf("bill", "payment", "402", "credit", "quota", "insufficient")
            .any { lower.contains(it) } ->
            ApiError.PaymentRequired()

        listOf("rate limit", "ratelimit", "429", "throttl", "too many")
            .any { lower.contains(it) } ->
            ApiError.RateLimited("limite Muse realtime atteinte : $detail")

        else -> ApiError.BadResponse("Muse realtime : $detail")
    }
}
