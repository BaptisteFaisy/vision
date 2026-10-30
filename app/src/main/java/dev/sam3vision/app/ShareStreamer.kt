package dev.sam3vision.app

import android.util.Log
import dev.sam3vision.app.isee.SharedCameraFrame
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.core.net.liveHttpClient
import dev.sam3vision.core.share.STREAM_FRAME_MS
import dev.sam3vision.core.share.STREAM_RETRY_MS
import dev.sam3vision.core.share.audienceWatching
import dev.sam3vision.core.share.cameraNotice
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
 * Trois règles de sobriété, apprises du partage lent :
 *  - **rien ne part sans spectateur** : le relais annonce le nombre d'aidants
 *    connectés (`{"type":"audience","watch":n}`), et la caméra se tait tant que
 *    ce nombre est nul — pas de données mobiles dépensées pour un écran fermé ;
 *  - **une frame n'est poussée qu'une fois** : le numéro de séquence de
 *    `SharedCameraFrame` évite de renvoyer la même image à chaque tour.
 *  - **la caméra dit quand elle s'éteint** : un avis `{"type":"camera","on":…}`
 *    à chaque changement, transmis par la salle aux aidants — l'image
 *    disparaît de leur page aussitôt, sans attendre que la dernière frame périme.
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

    /** Dernier état caméra annoncé ; `null` force une republication au tour suivant. */
    @Volatile
    private var announced: Boolean? = null

    /** Frames poussées, tours sans frame fraîche, envois refusés par le tuyau. */
    @Volatile
    var sentFrames = 0L
        private set

    @Volatile
    var droppedFrames = 0L
        private set

    @Volatile
    var sendFailures = 0L
        private set

    /** Intervalles d'envoi : le rythme **réel** du direct, pas la cadence demandée. */
    private val fps = LiveFpsMeter()
    private val gaps = LongArray(LOG_EVERY_SENDS) { 0L }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Chaque avis d'audience (aidant qui arrive ou part) republie l'état
            // caméra : un aidant qui vient d'arriver l'apprend dans le tour suivant.
            audienceWatching(text)?.let {
                watching = it
                announced = null
                SharedCameraFrame.audience = it
            }
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
            // Écran éteint, partage coupé : le tuyau se ferme vraiment, même sur
            // annulation — sinon la salle croirait le téléphone encore là et les
            // aidants garderaient l'image.
            try {
                pump(ws)
            } finally {
                ws.cancel()
                if (socket === ws) socket = null
                watching = false
                announced = null
                SharedCameraFrame.audience = false
            }
            if (!closed) delay(STREAM_RETRY_MS)
        }
    }

    /**
     * Pousse les frames tant que ce tuyau-là vit, à **échéance absolue** : un envoi
     * lent ne décale pas les suivants (pas de `delay` fixe qui dérive), le tour
     * suivant part à l'heure ou saute si l'échéance est déjà passée.
     */
    private suspend fun pump(ws: WebSocket) {
        var nextDue = System.currentTimeMillis()
        while (!closed && socket === ws) {
            nextDue += STREAM_FRAME_MS
            val wait = nextDue - System.currentTimeMillis()
            if (wait > 0) delay(wait) else nextDue = System.currentTimeMillis()
            // Sans spectateur, rien ne part — et le retour de l'audience republie l'état.
            if (!watching) { announced = null; continue }
            // Caméra fermée ou figée : les aidants l'apprennent dans le tour,
            // au lieu de garder la dernière image jusqu'à 8 s.
            val frame = SharedCameraFrame.latestBytes(System.currentTimeMillis())
            if (frame == null || frame.jpeg.isEmpty()) {
                droppedFrames += 1
                announce(ws, false)
                continue
            }
            if (frame.seq == sentSeq) continue
            sentSeq = frame.seq
            if (!ws.send(ByteString.of(*frame.jpeg))) {
                sendFailures += 1
                return
            }
            announce(ws, true)
            noteSent(System.currentTimeMillis())
        }
    }

    /**
     * Publie l'état de la caméra, une fois par changement : la salle le transmet
     * aux aidants, qui effacent l'image dès `false`. Les remises à `null`
     * (audience perdue, tuyau rouvert) forcent une republication au tour suivant.
     */
    private fun announce(ws: WebSocket, on: Boolean) {
        if (announced == on) return
        announced = on
        runCatching { ws.send(cameraNotice(on)) }
    }

    /** Un envoi de plus : l'intervalle rejoint la fenêtre, le log part tous les 60. */
    private fun noteSent(nowMs: Long) {
        val gap = fps.gapAt(nowMs)
        sentFrames += 1
        if (gap in 1..LiveFpsMeter.MAX_GAP_MS) gaps[(sentFrames % LOG_EVERY_SENDS).toInt()] = gap
        if (sentFrames % LOG_EVERY_SENDS == 0L) logFps()
    }

    /** Rythme réel du direct : médiane et p95 des intervalles d'envoi (N = 60). */
    private fun logFps() {
        val valid = gaps.filter { it > 0 }.sorted()
        if (valid.isEmpty()) return
        val median = valid[valid.size / 2]
        val p95 = valid[(valid.size * 95 / 100).coerceAtMost(valid.size - 1)]
        Log.i(
            "sam3vision",
            "direct: ${"%.1f".format(1000f / median)} ips " +
                "(médiane $median ms, p95 $p95 ms, " +
                "envoyées $sentFrames, jetées $droppedFrames, refus $sendFailures)",
        )
    }

    /** Ferme le direct (sortie de l'écran, partage coupé). */
    fun close() {
        closed = true
        watching = false
        // Dernier mot avant de raccrocher : la salle préviendra les aidants de
        // toute façon en voyant le tuyau se fermer, mais l'avis part avant.
        runCatching { socket?.send(cameraNotice(false)) }
        SharedCameraFrame.audience = false
        runCatching { socket?.close(1000, "fin") }
        socket = null
    }

    private fun drop(webSocket: WebSocket) {
        if (socket === webSocket) socket = null
        announced = null
        watching = false
        SharedCameraFrame.audience = false
    }

    companion object {
        /** Une ligne de rythme tous les 60 envois (≈ 4 s à 15 im/s). */
        private const val LOG_EVERY_SENDS = 60
    }
}
