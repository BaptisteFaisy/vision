package dev.sam3vision.core.net

import dev.sam3vision.core.audio.Wav
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Dictée temps réel ElevenLabs (Scribe v2 Realtime) par WebSocket.
 *
 * Le batch (`ElevenLabsSttClient`) envoie le WAV complet au relâchement puis
 * attend l'inférence sur tout l'énoncé : ici le PCM part **pendant** la tenue
 * (trames `input_audio_chunk`, base64 de PCM16 16 kHz mono — exactement ce que
 * le micro produit), le serveur répond des `partial_transcript` (~150 ms
 * annoncés) et, au relâchement, un chunk `commit:true` fige le segment en un
 * `committed_transcript`. Le relâchement ne coûte alors que la queue audio +
 * un aller-retour, au lieu d'un envoi complet + une inférence complète.
 *
 * Protocole : `GET /v1/speech-to-text/realtime` (référence AsyncAPI officielle,
 * relue le 2026-10-31), `commit_strategy=manual`, `audio_format=pcm_16000`,
 * clé en en-tête `xi-api-key` — le même modèle de confiance que le batch
 * (la clé est déjà côté téléphone, aucun jeton single-use à frapper).
 *
 * Cadrage d'envoi : le PCM est découpé en trames de 100 ms, **sauf la dernière
 * qui reste en rétention** : le commit a toujours du son à porter (`audio_base_64`
 * est obligatoire), même quand tout le reste est déjà parti. Avant
 * `session_started`, tout est tamponné (borné : [PENDING_CAP_BYTES]) puis
 * flushé — le handshake (~0,5 s) ne perd jamais le début de l'énoncé.
 *
 * Le parsing se fait sur le thread lecteur d'OkHttp : les trames Scribe font
 * ~100 octets à ~2 Ko à ~10 trames/s, pas ~28 Ko à 30 ips comme le direct —
 * la règle « zéro JSON sur le lecteur » de [WsLiveClient] ne s'y applique pas.
 */
interface ScribeStreamSession {
    /** Ouvre le socket et attend `session_started` ([Result.failure] sinon). */
    suspend fun start(): Result<Unit>

    /** Enfile [count] octets de PCM16 16 kHz ; `false` = session morte. */
    fun sendPcm(chunk: ByteArray, count: Int = chunk.size): Boolean

    /**
     * Envoie la queue retenue avec `commit:true`, attend le
     * `committed_transcript` puis ferme. Texte brut (le cas d'usage décide
     * quoi faire d'un texte vide, comme pour le batch).
     */
    suspend fun commitAndFinal(): Result<String>

    /** Ferme sans commit (annulation, repli batch, tenue trop courte). */
    fun abort()

    /** Vrai entre `session_started` et la mort/fermeture du socket. */
    val isLive: Boolean
}

/** Une session de dictée temps réel : ouvrir, streamer, commiter, fermer. */
class ScribeRealtimeSession(
    private val apiKey: String,
    private val modelId: String = DEFAULT_REALTIME_MODEL,
    private val baseUrl: String = DEFAULT_REALTIME_BASE_URL,
    private val transport: WsTransport = OkHttpWsTransport(scribeWsClient()),
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val commitTimeoutMs: Long = COMMIT_TIMEOUT_MS,
    /** Partiel reçu (thread lecteur) : l'appelant filtre (tenue, écran). */
    private val onPartial: (String) -> Unit = {},
    /** Session morte **après** `session_started` : l'appelant dégrade (batch). */
    private val onError: (ApiError) -> Unit = {},
) : ScribeStreamSession {

    private val lock = Any()
    private val started = CompletableDeferred<Unit>()
    private val committed = CompletableDeferred<String>()
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
            return Result.failure(ApiError.InvalidKey("clé ElevenLabs absente — renseigne-la dans Réglages"))
        }
        val opened = synchronized(lock) {
            if (dead) return Result.failure(ApiError.Network(WS_UNREACHABLE))
            transport.open(
                scribeRealtimeUrl(baseUrl, modelId),
                { text -> onText(text) },
                { error -> onClosed(error) },
                headers = mapOf(API_KEY_HEADER to apiKey),
            )
        }
        if (!opened) {
            synchronized(lock) { dead = true }
            return Result.failure(ApiError.Network(WS_UNREACHABLE))
        }
        // Tri-état : succès, mort avec sa cause, ou vrai silence (délai).
        val outcome: Result<Unit>? = withTimeoutOrNull(startTimeoutMs) {
            try {
                started.await()
                Result.success(Unit)
            } catch (e: ApiError) {
                Result.failure(e)
            }
        }
        if (outcome == null) {
            synchronized(lock) { dead = true }
            runCatching { transport.close() }
            return Result.failure(ApiError.Timeout("Scribe realtime : session non démarrée"))
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
                die(ApiError.BadResponse("Scribe realtime : tampon plein, session non démarrée"))
                return false
            }
            pending.addLast(copy)
            pendingBytes += count
            streamedBytes += count
            if (!live) return true
            return flushLocked()
        }
    }

    override suspend fun commitAndFinal(): Result<String> {
        val tail: ByteArray = synchronized(lock) {
            if (dead) return fail(committedError())
            if (streamedBytes == 0L) {
                done = true
                runCatching { transport.close() }
                return Result.failure(ApiError.BadResponse("audio vide"))
            }
            takeLocked(pendingBytes)
        }
        val sent = synchronized(lock) {
            if (dead) return fail(committedError())
            transport.send(chunkMessage(tail, commit = true))
        }
        if (!sent) return fail(ApiError.Network("Scribe realtime : envoi refusé"))
        val text = withTimeoutOrNull(commitTimeoutMs) {
            try {
                committed.await()
            } catch (e: ApiError) {
                return fail(e)
            }
        }
        synchronized(lock) { done = true }
        runCatching { transport.close() }
        if (text == null) return fail(ApiError.Timeout("Scribe realtime : commit sans réponse"))
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

    /** Trames pleines de 100 ms, la dernière restant en rétention pour le commit. */
    private fun flushLocked(): Boolean {
        while (pendingBytes > FRAME_BYTES) {
            val frame = takeLocked(FRAME_BYTES)
            if (!transport.send(chunkMessage(frame, commit = false))) {
                die(ApiError.Network("Scribe realtime : envoi refusé"))
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
        started.completeExceptionally(cause)
        committed.completeExceptionally(cause)
    }

    private fun fail(cause: ApiError): Result<String> {
        die(cause)
        return Result.failure(cause)
    }

    private fun committedError(): ApiError =
        ApiError.Network("Scribe realtime : session fermée avant le commit")

    /** Message entrant (thread lecteur) : petit, parsé ici (voir KDoc). */
    private fun onText(text: String) {
        val event = parseScribeEvent(text) ?: return
        when (event) {
            is ScribeEvent.Started -> onStarted()
            is ScribeEvent.Partial -> onPartial(event.text)
            is ScribeEvent.Committed -> onCommitted(event.text)
            is ScribeEvent.Failed -> die(event.error)
            ScribeEvent.Ignored -> Unit
        }
    }

    private fun onStarted() {
        synchronized(lock) {
            if (dead || done) return
            live = true
            born = true
            flushLocked()
        }
        started.complete(Unit)
    }

    private fun onCommitted(text: String) {
        synchronized(lock) {
            if (dead || done) return
            done = true
        }
        committed.complete(text)
    }

    private fun onClosed(error: Throwable?) {
        // `done` est posé sous le même verrou par [onCommitted] : un commit
        // reçu juste avant la fermeture reste un succès, jamais une erreur.
        val cause = synchronized(lock) {
            if (done) null else (error?.toApiError() ?: ApiError.Network(WS_CLOSED))
        } ?: return
        die(cause)
    }

    companion object {
        const val DEFAULT_REALTIME_MODEL = "scribe_v2_realtime"
        const val DEFAULT_REALTIME_BASE_URL = "https://api.elevenlabs.io"
        const val REALTIME_PATH = "/v1/speech-to-text/realtime"
        const val API_KEY_HEADER = "xi-api-key"

        /** Handshake + `session_started` : large, un réseau mobile poussif. */
        const val START_TIMEOUT_MS = 10_000L

        /** Commit : queue audio + inférence de fin de segment. */
        const val COMMIT_TIMEOUT_MS = 15_000L

        /** 100 ms de PCM16 16 kHz mono par trame. */
        const val FRAME_BYTES = 3_200

        /** ~10 s d'audio en attente du `session_started`, jamais plus. */
        const val PENDING_CAP_BYTES = 320_000

        const val WS_UNREACHABLE = "Scribe realtime injoignable"
        const val WS_CLOSED = "Scribe realtime : session fermée"
    }
}

/** Événement Scribe parsé : seuls `Started`/`Partial`/`Committed`/`Failed` agissent. */
internal sealed interface ScribeEvent {
    data object Started : ScribeEvent
    data class Partial(val text: String) : ScribeEvent
    data class Committed(val text: String) : ScribeEvent
    data class Failed(val error: ApiError) : ScribeEvent
    data object Ignored : ScribeEvent
}

/** `wss://` + chemin realtime + `model_id`, `commit_strategy=manual`, `pcm_16000`. */
internal fun scribeRealtimeUrl(baseUrl: String, modelId: String): String =
    webSocketUrl(baseUrl, ScribeRealtimeSession.REALTIME_PATH) +
        "?model_id=$modelId&commit_strategy=manual&audio_format=pcm_16000"

/**
 * Trame `input_audio_chunk` : base64 sans échappement (l'alphabet base64 ne
 * contient aucun caractère JSON spécial), `sample_rate` = taux du micro.
 */
internal fun chunkMessage(pcm: ByteArray, commit: Boolean): String {
    val b64 = Base64.getEncoder().encodeToString(pcm)
    return """{"message_type":"input_audio_chunk","audio_base_64":"$b64",""" +
        """"commit":$commit,"sample_rate":${Wav.SAMPLE_RATE}}"""
}

/**
 * Réponse Scribe : `session_started` / `partial_transcript` /
 * `committed_transcript(_with_timestamps)` / erreurs typées (`auth_error`,
 * `quota_exceeded`, `rate_limited`, …). `warning` et inconnus ignorés ;
 * JSON illisible ignoré (le socket parle encore, la session vit).
 */
internal fun parseScribeEvent(raw: String): ScribeEvent? {
    val json: JSONObject = parse(raw) ?: return null
    return when (json.str("message_type")) {
        "session_started" -> ScribeEvent.Started
        "partial_transcript" -> ScribeEvent.Partial(json.str("text"))
        "committed_transcript", "committed_transcript_with_timestamps" ->
            ScribeEvent.Committed(json.str("text"))

        "auth_error" -> ScribeEvent.Failed(ApiError.InvalidKey("clé ElevenLabs invalide (realtime)"))
        "quota_exceeded" -> ScribeEvent.Failed(ApiError.PaymentRequired())
        "rate_limited", "commit_throttled" -> ScribeEvent.Failed(
            ApiError.RateLimited("limite Scribe realtime atteinte : ${json.str("error").take(120)}"),
        )

        "error", "unaccepted_terms", "queue_overflow", "resource_exhausted",
        "session_time_limit_exceeded", "input_error", "invalid_request",
        "chunk_size_exceeded", "insufficient_audio_activity", "transcriber_error" -> {
            val type = json.str("message_type")
            ScribeEvent.Failed(ApiError.BadResponse("Scribe realtime ($type) : ${json.str("error").take(120)}"))
        }
        // `warning`, vide, futurs événements informatifs : ignorés, la session vit.
        else -> ScribeEvent.Ignored
    }
}
