package dev.sam3vision.core.share

import dev.sam3vision.core.net.OneShotJsonBody
import dev.sam3vision.core.net.execute
import dev.sam3vision.core.net.liveHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Résultat d'un envoi. Le refus du relais est un fait à part entière : avalé, il
 * donnait une carte vide indiscernable d'un proche qui n'a rien à envoyer.
 */
sealed interface ShareSend {
    /** Le relais a pris l'état. */
    data object Sent : ShareSend

    /** Cadence : rien à envoyer avant [ShareReporter] n'ait laissé passer le délai. */
    data object Skipped : ShareSend

    /** Le relais a répondu hors 2xx (`403` = clé du flux différente). */
    data class Refused(val code: Int) : ShareSend

    /** Aucune réponse : réseau, DNS, TLS, délai dépassé. */
    data object Unreachable : ShareSend
}

/** Message montré dans les Réglages ; `null` = rien à signaler. */
fun shareSendMessage(result: ShareSend): String? = when (result) {
    ShareSend.Sent, ShareSend.Skipped -> null
    is ShareSend.Refused ->
        if (result.code == 403) "Le relais a refusé l'envoi : la clé de ce téléphone n'est pas celle du flux."
        else "Le relais a refusé l'envoi (code ${result.code})."
    ShareSend.Unreachable -> "Relais de suivi injoignable : vérifie la connexion du téléphone."
}

/**
 * Envoie l'état du partage au relais, au plus toutes les [minIntervalMs].
 * Rend le résultat au lieu d'un booléen : l'appelant peut le dire à l'utilisateur.
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

/** Cadence d'envoi du partage : un état toutes les 2 s pendant le Direct. */
const val SHARE_INTERVAL_MS = 2_000L
