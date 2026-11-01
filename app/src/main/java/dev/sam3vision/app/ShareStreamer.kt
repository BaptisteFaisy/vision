package dev.sam3vision.app

import android.util.Log
import dev.sam3vision.app.isee.SharedCameraFrame
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.core.net.liveHttpClient
import dev.sam3vision.core.share.STREAM_FRAME_MS
import dev.sam3vision.core.share.STREAM_RETRY_MS
import dev.sam3vision.core.share.audienceWatching
import dev.sam3vision.core.share.cameraNotice
import dev.sam3vision.core.share.cameraOffReady
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Live stream from the loved one's camera to the caregiver page: a WebSocket
 * to the stream room (`/ws?role=push`), with binary JPEG frames at
 * [STREAM_FRAME_MS]. The D1 relay keeps carrying position, sentence and
 * trip — the picture passes **only** through this pipe, it is never stored.
 *
 * Three frugality rules, learned from slow sharing:
 *  - **nothing leaves without a watcher**: the relay announces the number of
 *    connected caregivers (`{"type":"audience","watch":n}`), and the camera
 *    stays quiet while that number is zero — no mobile data spent for a
 *    closed screen;
 *  - **a frame is pushed only once**: the `SharedCameraFrame` sequence number
 *    avoids resending the same picture every turn.
 *  - **the camera tells when it turns off**: a `{"type":"camera","on":…}`
 *    notice on every change, forwarded by the room to caregivers — the picture
 *    leaves their page at once, without waiting for the last frame to expire.
 *
 * A drop breaks nothing: the pipe reopens after [STREAM_RETRY_MS], and the
 * page falls back to the slow photo while no frame arrives.
 */
class ShareStreamer(private val client: OkHttpClient = liveHttpClient()) {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var watching = false

    @Volatile
    private var closed = false

    private var sentSeq = -1L

    /** Last announced camera state; `null` forces a republication next turn. */
    @Volatile
    private var announced: Boolean? = null

    /** Empty turns in a row: only lasting silence announces the camera off. */
    private var emptyTurns = 0

    /** Frames pushed, turns with no fresh frame, sends refused by the pipe. */
    @Volatile
    var sentFrames = 0L
        private set

    @Volatile
    var droppedFrames = 0L
        private set

    @Volatile
    var sendFailures = 0L
        private set

    /** Send gaps: the **real** live rhythm, not the requested rate. */
    private val fps = LiveFpsMeter()
    private val gaps = LongArray(LOG_EVERY_SENDS) { 0L }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Every audience notice (caregiver arriving or leaving) republishes
            // the camera state: a just-arrived caregiver learns it next turn.
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

    /** Keeps the live stream open until [close]: reopened after every drop. */
    suspend fun run(url: String) {
        while (!closed) {
            val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            socket = ws
            // Screen off, share cut: the pipe really closes, even on
            // cancellation — otherwise the room would believe the phone still
            // there and caregivers would keep the picture.
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
     * Pushes frames while this pipe lives, on **absolute deadline**: a slow
     * send never shifts the next ones (no drifting fixed `delay`), the next
     * turn leaves on time or skips when the deadline already passed.
     */
    private suspend fun pump(ws: WebSocket) {
        var nextDue = System.currentTimeMillis()
        while (!closed && socket === ws) {
            nextDue += STREAM_FRAME_MS
            val wait = nextDue - System.currentTimeMillis()
            if (wait > 0) delay(wait) else nextDue = System.currentTimeMillis()
            // With no watcher, nothing leaves — and the audience return republishes the state.
            if (!watching) { announced = null; emptyTurns = 0; continue }
            // Closed or frozen camera: caregivers learn it after the grace,
            // instead of keeping the last picture up to 8 s. A lone empty
            // turn turns nothing off, or every micro-gap would flicker their page.
            val frame = SharedCameraFrame.latestBytes(System.currentTimeMillis())
            if (frame == null || frame.jpeg.isEmpty()) {
                droppedFrames += 1
                emptyTurns += 1
                if (cameraOffReady(emptyTurns)) announce(ws, false)
                continue
            }
            emptyTurns = 0
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
     * Publishes the camera state, once per change: the room forwards it to
     * caregivers, who clear the picture on `false`. Resets to `null` (lost
     * audience, reopened pipe) force a republication next turn.
     */
    private fun announce(ws: WebSocket, on: Boolean) {
        if (announced == on) return
        announced = on
        runCatching { ws.send(cameraNotice(on)) }
    }

    /** One more send: the gap joins the window, the log goes every 60. */
    private fun noteSent(nowMs: Long) {
        val gap = fps.gapAt(nowMs)
        sentFrames += 1
        if (gap in 1..LiveFpsMeter.MAX_GAP_MS) gaps[(sentFrames % LOG_EVERY_SENDS).toInt()] = gap
        if (sentFrames % LOG_EVERY_SENDS == 0L) logFps()
    }

    /** Real live rhythm: median and p95 of send gaps (N = 60). */
    private fun logFps() {
        val valid = gaps.filter { it > 0 }.sorted()
        if (valid.isEmpty()) return
        val median = valid[valid.size / 2]
        val p95 = valid[(valid.size * 95 / 100).coerceAtMost(valid.size - 1)]
        Log.i(
            "sam3vision",
            "direct: ${"%.1f".format(1000f / median)} fps " +
                "(median $median ms, p95 $p95 ms, " +
                "sent $sentFrames, dropped $droppedFrames, refused $sendFailures)",
        )
    }

    /** Closes the live stream (leaving the screen, share cut). */
    fun close() {
        closed = true
        watching = false
        // Last word before hanging up: the room would tell caregivers anyway
        // when it sees the pipe close, but the notice goes first.
        runCatching { socket?.send(cameraNotice(false)) }
        SharedCameraFrame.audience = false
        runCatching { socket?.close(1000, "done") }
        socket = null
    }

    private fun drop(webSocket: WebSocket) {
        if (socket === webSocket) socket = null
        announced = null
        watching = false
        SharedCameraFrame.audience = false
    }

    companion object {
        /** One rhythm line every 60 sends (≈ 4 s at 15 fps). */
        private const val LOG_EVERY_SENDS = 60
    }
}
