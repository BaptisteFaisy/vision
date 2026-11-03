package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * Textes d'erreur du direct : une seule source, hors de [WsLiveClient] (garde des 500
 * lignes). Ce sont **les messages que lit l'écran** quand une frame n'aboutit pas : ils
 * doivent donc dire la cause réelle (hors ligne, réseau changé, reconnexion en cours,
 * silence) et pas un « unreachable » générique.
 */
internal const val WS_UNREACHABLE = "WebSocket /live/ws unreachable"
internal const val WS_CLOSED = "WebSocket /live/ws closed"
internal const val WS_UNREADABLE = "WebSocket: unreadable message"
internal const val WS_BAD_IMAGE = "WebSocket /live/ws : image_b64 illisible"

/** La frame n'a pas quitté le téléphone (hors ligne connu). */
internal const val WS_OFFLINE = "WebSocket /live/ws : hors ligne"

/** Interface changée : le socket de l'ancienne est mort-né. */
internal const val WS_IFACE_CHANGED = "WebSocket /live/ws : réseau changé"

/** Backoff en cours : la reprise est armée, inutile d'empiler les handshakes. */
internal const val WS_RECONNECTING = "WebSocket /live/ws : reconnexion en cours"

/** Le socket n'a plus donné signe de vie : le watchdog l'a déclaré mort. */
internal const val WS_SILENT = "WebSocket /live/ws : silence, socket mort"
internal const val WS_SEND_REFUSED = "WebSocket /live/ws: send refused"

/** `id` d'une réponse orpheline (serveur sans binaire) et son marqueur d'erreur. */
internal const val UNKNOWN_WS_ID = -1
internal const val ERROR_MARK = "\"error\""

/** Fermeture idle serveur (120 s sans message applicatif) : reprise sans backoff (§9). */
internal const val IDLE_CLOSE_CODE = 1001

/** Annulation one-way (FIX-8) : le serveur annule l'inférence abandonnée, sans répondre. */
internal fun cancelMessage(id: Int): String = "{\"id\":$id,\"cancel\":true}"

/** Fermeture propre : la seule valeur que `closeCodeOf` invente (transport = `null`). */
internal const val NORMAL_CLOSE_CODE = 1000

/** Une frame en vol : son réveil, et si elle est partie en binaire (repli JSON). */
internal class PendingFrame(val reply: CompletableDeferred<String>, val binary: Boolean)

/** Signal interne : `id: -1` reçu — la frame binaire en vol repart en JSON. */
internal object FallbackToJson : Exception("WS binaire refusé (id -1) : repli JSON")

/** Message de requête : le corps de `POST /live` (mêmes knobs) plus l'`id` de corrélation. */
internal fun frameMessage(
    id: Int,
    jpegB64: String,
    prompts: List<String>?,
    workEdge: Int,
    maxObjects: Int,
    maskFormat: String?,
    maskEdge: Int,
    withDepth: Boolean,
    masks: Boolean,
): String = liveFrameJson(jpegB64, prompts, workEdge, maxObjects, id, maskFormat, maskEdge, withDepth, masks)

/** Trame M1 : les knobs du client appliqués à [frameMessageBinary]. */
internal fun binaryFrame(
    id: Int,
    jpeg: ByteArray,
    prompts: List<String>?,
    workEdge: Int,
    maxObjects: Int,
    maskFormat: String?,
    maskEdge: Int,
    withDepth: Boolean,
    masks: Boolean,
): ByteArray = frameMessageBinary(id, jpeg, prompts, workEdge, maxObjects, maskFormat, maskEdge, withDepth, masks)

/** Réponse serveur : JSON construit **une seule fois**, partagé par garde et frame. */
internal fun parseReply(raw: String, readJson: (String) -> JSONObject?): Result<LiveFrame> {
    val json = readJson(raw) ?: return Result.failure(ApiError.BadResponse(WS_UNREADABLE))
    return liveReplyOf(json)
}
