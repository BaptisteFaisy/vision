package dev.sam3vision.core.net

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
