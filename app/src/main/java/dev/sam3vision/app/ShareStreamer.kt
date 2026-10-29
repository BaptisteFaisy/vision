package dev.sam3vision.app

import android.util.Base64
import dev.sam3vision.app.isee.SharedCameraFrame
import dev.sam3vision.core.net.liveHttpClient
import dev.sam3vision.core.share.STREAM_FRAME_MS
import dev.sam3vision.core.share.STREAM_RETRY_MS
import dev.sam3vision.core.share.audienceWatching
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Direct de la caméra du proche vers la page de l'aidant : un WebSocket vers la
 * salle du flux (`/ws?role=push`), et des frames JPEG binaires à
 * [STREAM_FRAME_MS]. Le relais D1 continue de porter position, phrase et trajet —
 * l'image ne passe **que** par ce tuyau, elle n'est jamais stockée.
 *
 * Deux règles de sobriété, apprises du partage lent :
 *  - **rien ne part sans spectateur** : le relais annonce le nombre d'aidants
 *    connectés (`{"type":"audience","watch":n}`), et la caméra se tait tant que
 *    ce nombre est nul — pas de données mobiles dépensées pour un écran fermé ;
 *  - **une frame n'est poussée qu'une fois** : le numéro de séquence de
 *    `SharedCameraFrame` évite de renvoyer la même image à chaque tour.
 *
 * Une coupure ne casse rien : le tuyau est rouvert après [STREAM_RETRY_MS], et
 * la page retombe sur la photo lente tant qu'aucune frame n'arrive.
 */
class ShareStreamer(private val client: OkHttpClient = liveHttpClient()) {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var watching = false

    @Volatile
    private var closed = false

    private var sentSeq = -1L

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            audienceWatching(text)?.let { watching = it }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            drop(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            drop(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            drop(webSocket)
        }
    }

    /** Garde le direct ouvert jusqu'à [close] : réouverture après chaque coupure. */
    suspend fun run(url: String) {
        while (!closed) {
            val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            socket = ws
            pump(ws)
            ws.cancel()
            if (socket === ws) socket = null
            watching = false
            if (!closed) delay(STREAM_RETRY_MS)
        }
    }

    /** Pousse les frames tant que ce tuyau-là vit. */
    private suspend fun pump(ws: WebSocket) {
        while (!closed && socket === ws) {
            delay(STREAM_FRAME_MS)
            if (!watching) continue
            val frame = SharedCameraFrame.latest(System.currentTimeMillis()) ?: continue
            if (frame.seq == sentSeq) continue
            sentSeq = frame.seq
            val bytes = runCatching { Base64.decode(frame.jpegBase64, Base64.DEFAULT) }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            if (!ws.send(ByteString.of(*bytes))) return
        }
    }

    /** Ferme le direct (sortie de l'écran, partage coupé). */
    fun close() {
        closed = true
        watching = false
        runCatching { socket?.close(1000, "fin") }
        socket = null
    }

    private fun drop(webSocket: WebSocket) {
        if (socket === webSocket) socket = null
        watching = false
    }
}
