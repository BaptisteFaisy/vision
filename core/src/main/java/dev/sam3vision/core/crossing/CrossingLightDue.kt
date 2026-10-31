package dev.sam3vision.core.crossing

/**
 * Échéance du statut traverser/pas-traverser : le statut parle toutes les
 * 2 s ([CrossingAnnouncer]) mais l'évitement peut le couvrir — jamais plus
 * de [LIGHT_MAX_SILENCE_MS] sans qu'il perce (ordre user du 31/10 : le
 * statut est obligatoire toutes les 4 s). Marqué quand le statut est dit,
 * pas quand il est couvert : seule la parole recule l'échéance.
 *
 * Pur et testé en JVM : [atMs] est fourni, aucune horloge implicite.
 */
class CrossingLightDue(
    private val maxSilenceMs: Long = LIGHT_MAX_SILENCE_MS,
) {

    private var lastSpokenAtMs: Long? = null

    /**
     * Vrai si le statut doit parler à [atMs] : jamais dit, ou muet depuis
     * [maxSilenceMs]. Un statut couvert ne recule rien.
     */
    fun due(atMs: Long): Boolean {
        val last = lastSpokenAtMs ?: return true
        return atMs - last >= maxSilenceMs
    }

    /** Le statut vient d'être dit à [atMs] : l'évitement peut le couvrir à nouveau. */
    fun markSpoken(atMs: Long) {
        lastSpokenAtMs = atMs
    }

    /** Nouvelle session : le prochain statut parlera. */
    fun reset() {
        lastSpokenAtMs = null
    }

    companion object {
        /** Silence maximal du statut : au-delà, il couvre l'évitement (4 s). */
        const val LIGHT_MAX_SILENCE_MS = 4_000L
    }
}
