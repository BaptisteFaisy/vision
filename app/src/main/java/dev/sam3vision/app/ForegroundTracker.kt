package dev.sam3vision.app

/**
 * Suit le premier plan pour un flux SAM (Direct ou sonde iSee) : en arrière-plan les
 * frames sont jetées, au retour le flux coupé repart. Un seul endroit pour la règle
 * « la carte ne travaille que quand l'app est au premier plan ».
 */
class ForegroundTracker {
    /** Faux dès `ON_PAUSE` : les frames reçues doivent être jetées. */
    var foreground = true
        private set

    /** Vrai si le flux a été coupé par [onBackground] et doit repartir au retour. */
    var autoPaused = false
        private set

    /**
     * Marque la sortie du premier plan. [running] dit si un flux était actif — lui
     * seul repartira tout seul (un arrêt manuel ne repart pas).
     */
    fun onBackground(running: Boolean) {
        foreground = false
        autoPaused = running
    }

    /**
     * Marque le retour au premier plan. Vrai s'il faut relancer le flux (coupé par
     * [onBackground], pas arrêté à la main).
     */
    fun onForeground(): Boolean {
        foreground = true
        if (!autoPaused) return false
        autoPaused = false
        return true
    }

    /** Un démarrage ou un arrêt manuel annule toute pause automatique. */
    fun reset() {
        foreground = true
        autoPaused = false
    }
}
