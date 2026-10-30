package dev.sam3vision.core.crossing

/**
 * Silence de la voix douce après un ordre du feu : la mémoire partagée des
 * voix douces du mode Traversée (personnes, véhicules).
 *
 * Un ordre du feu **nouveau** (phrase jamais vue) fait taire la voix douce
 * pendant [quietMs] — le temps de le synthétiser et de le dire : sans ce
 * silence, le `collectLatest` côté sonde jetterait l'ordre pas encore joué
 * dès qu'une voix douce parlerait, et un « Vert, traversez. » dit une seule
 * fois ne se rattrape pas. Les répétitions (même phrase, comme le rouge
 * redit toutes les 2 s) ne réarment pas le silence.
 *
 * Pur et testé en JVM : [atMs] est fourni, aucune horloge implicite.
 */
class CrossingCueQuiet(
    private val quietMs: Long = CUE_QUIET_MS,
) {

    private var lastPhrase: String? = null
    private var lastMs: Long = Long.MIN_VALUE

    /**
     * Vrai si la voix douce doit se taire sur cette frame : le feu parle
     * ([cue] non nul), ou son ordre nouveau est encore récent. Nourrir à
     * **chaque** frame, même muette : un ordre manqué ne protège rien.
     */
    fun hush(cue: CrossingCue?, atMs: Long): Boolean {
        if (cue != null && cue.phrase != lastPhrase) {
            lastPhrase = cue.phrase
            lastMs = atMs
        }
        if (cue != null) return true
        return lastMs != Long.MIN_VALUE && atMs >= lastMs && atMs - lastMs < quietMs
    }

    /** Nouvelle session : le prochain ordre parlera, la voix douce aussi. */
    fun reset() {
        lastPhrase = null
        lastMs = Long.MIN_VALUE
    }

    companion object {
        /**
         * Silence doux après un ordre du feu nouveau (2,5 s) : une instance
         * par voix douce, toutes nourries du même ordre, donc toutes muettes
         * ensemble.
         */
        const val CUE_QUIET_MS = 2_500L
    }
}
