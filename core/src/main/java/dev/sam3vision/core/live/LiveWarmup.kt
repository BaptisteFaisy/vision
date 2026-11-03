package dev.sam3vision.core.live

/**
 * Premier succès d'une séance du direct : le conteneur est chaud, le délai froid
 * (démarrage Modal) retombe au délai normal — une fois par séance. Pur et testé
 * en JVM : l'écran resserre son propre client quand [onFirstSuccess] rend vrai.
 */
class LiveWarmup {

    /** Vrai dès le premier succès, jusqu'au prochain [reset]. */
    var warmed: Boolean = false
        private set

    /**
     * Succès d'une frame : `true` la première fois (l'appelant resserre son
     * délai froid), `false` ensuite — le budget froid ne se paie qu'une fois.
     */
    fun onFirstSuccess(): Boolean {
        if (warmed) return false
        warmed = true
        return true
    }

    /** Nouvelle séance : le prochain succès refera chaud. */
    fun reset() {
        warmed = false
    }
}
