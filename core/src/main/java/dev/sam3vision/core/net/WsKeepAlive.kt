package dev.sam3vision.core.net

/** Pause sans frame avant un ping applicatif : sous l'idle serveur (45 s), avec marge. */
internal const val PING_INTERVAL_MS = 30_000L

/** Premier `id` des pings : négatif, hors de l'espace des frames (`-1` = refus binaire). */
internal const val FIRST_PING_ID = -2

/** Message keepalive (FIX-17) : le serveur répond `{"id":N,"pong":true}` sans GPU. */
internal fun pingMessage(id: Int): String = "{\"type\":\"ping\",\"id\":$id}"

/**
 * Décision du keepalive applicatif (FIX-17) : les pings protocole ne resettent pas
 * l'idle serveur (45 s → 1011), seul un message applicatif le fait. [poll] dit s'il
 * faut pinger ([clock] injectable : les tests prouvent le délai sans attendre).
 */
class WsKeepAlive(
    val intervalMs: Long = PING_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastActiveMs = clock()

    /** Une frame part : le prochain ping recule d'un intervalle plein. */
    @Synchronized
    fun markActive() {
        lastActiveMs = clock()
    }

    /**
     * Faut-il pinger maintenant ? Non si une frame est en vol (le socket prouve qu'il
     * vit), non avant l'intervalle ; oui après — et l'envoi est acté (un seul ping
     * par tick, même si le minuteur bat plus vite que l'intervalle).
     */
    @Synchronized
    fun poll(inFlight: Boolean): Boolean {
        if (inFlight) return false
        val now = clock()
        if (now - lastActiveMs < intervalMs) return false
        lastActiveMs = now
        return true
    }
}
