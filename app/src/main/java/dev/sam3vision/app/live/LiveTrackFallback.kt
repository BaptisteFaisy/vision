package dev.sam3vision.app.live

/**
 * Repli automatique tracker → `/live` : si le tracker n'a jamais répondu sur la
 * séance (backend track non déployé ou injoignable), le direct relance en WebSocket
 * au lieu d'afficher « injoignable » — le direct marche toujours, au pire plus lent.
 * Une seule fois par séance : si `/live` échoue aussi, l'erreur s'affiche vraiment.
 */
class LiveTrackFallback {

    private var used = false

    /** Nouvelle séance : le repli est de nouveau disponible. */
    fun reset() {
        used = false
    }

    /**
     * Vrai s'il faut replier : [starting] (aucune frame publiée) et transport
     * tracker. Le premier appel qui replie consomme le repli.
     */
    fun shouldFallBack(starting: Boolean, onTracker: Boolean): Boolean {
        if (!starting || !onTracker || used) return false
        used = true
        return true
    }
}
