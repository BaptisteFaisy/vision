package dev.sam3vision.core.share

import dev.sam3vision.core.net.OneShotJsonBody
import dev.sam3vision.core.net.execute
import dev.sam3vision.core.net.liveHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Envoie l'état du partage au relais, au plus toutes les [minIntervalMs].
 * Silencieux en cas d'échec (le site garde le dernier état et l'affiche périmé) ;
 * `false` = non envoyé (throttle ou erreur réseau/HTTP).
 */
class ShareReporter(
    private val client: OkHttpClient = liveHttpClient(),
    private val minIntervalMs: Long = SHARE_INTERVAL_MS,
) {
    private var lastSentAtMs: Long = 0L

    suspend fun maybeSend(nowMs: Long, url: String, payload: JSONObject): Boolean {
        if (nowMs - lastSentAtMs < minIntervalMs) return false
        lastSentAtMs = nowMs
        val request = Request.Builder().url(url).post(OneShotJsonBody(payload.toString())).build()
        return client.execute(request).fold(
            onSuccess = { it.code in 200..299 },
            onFailure = { false },
        )
    }

    fun reset() {
        lastSentAtMs = 0L
    }
}

/** Cadence d'envoi du partage : un état toutes les 2 s pendant le Direct. */
const val SHARE_INTERVAL_MS = 2_000L
