package dev.sam3vision.core.share

import dev.sam3vision.core.net.OneShotJsonBody
import dev.sam3vision.core.net.execute
import dev.sam3vision.core.net.liveHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Result of a send. A relay refusal is a full fact: swallowed, it yielded
 * an empty map indistinguishable from a loved one with nothing to send.
 */
sealed interface ShareSend {
    /** The relay took the state. */
    data object Sent : ShareSend

    /** Rate: nothing to send before [ShareReporter] let the delay pass. */
    data object Skipped : ShareSend

    /** The relay answered outside 2xx (`403` = different stream key). */
    data class Refused(val code: Int) : ShareSend

    /** No answer: network, DNS, TLS, timeout. */
    data object Unreachable : ShareSend
}

/** Message shown in Settings; `null` = nothing to report. */
fun shareSendMessage(result: ShareSend): String? = when (result) {
    ShareSend.Sent, ShareSend.Skipped -> null
    is ShareSend.Refused ->
        if (result.code == 403) "The relay refused the send: this phone's key is not the stream's key."
        else "The relay refused the send (code ${result.code})."
    ShareSend.Unreachable -> "Tracking relay unreachable: check the phone's connection."
}

/**
 * Sends the share state to the relay, at most every [minIntervalMs].
 * Returns the result instead of a boolean: the caller can tell the user.
 */
class ShareReporter(
    private val client: OkHttpClient = liveHttpClient(),
    private val minIntervalMs: Long = SHARE_INTERVAL_MS,
) {
    private var lastSentAtMs: Long = 0L

    suspend fun maybeSend(nowMs: Long, url: String, payload: JSONObject): ShareSend {
        if (nowMs - lastSentAtMs < minIntervalMs) return ShareSend.Skipped
        lastSentAtMs = nowMs
        val request = Request.Builder().url(url).post(OneShotJsonBody(payload.toString())).build()
        return client.execute(request).fold(
            onSuccess = { if (it.code in 200..299) ShareSend.Sent else ShareSend.Refused(it.code) },
            onFailure = { ShareSend.Unreachable },
        )
    }

    fun reset() {
        lastSentAtMs = 0L
    }
}

/** Share send rate: one state every 2 s during the Direct. */
const val SHARE_INTERVAL_MS = 2_000L
