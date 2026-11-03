package dev.sam3vision.core.curb

/**
 * Boucle « curb up » : vrai en continu tant qu'une montée est à moins
 * d'1 m ([ENTER_METERS]), faux sinon — l'appelant y re-poste le clip
 * [dev.sam3vision.core.speech.IseeClipsEn.CURB_UP] à chaque frame libre,
 * sans jamais couper la lecture en cours (porte `curbSpeaking` côté bus +
 * dé-dup des re-posts identiques côté lecteur).
 *
 * Confirmation (2 frames) et sortie ([EXIT_MISSES] manqués, hystérésis
 * [EXIT_METERS]) : le clignotement SAM ne fait ni bégayer ni lâcher la
 * boucle. Sens montant seul (`Up`, `null` = construction manuelle lue
 * comme une montée) : une descente ou un sens incertain garde la voix
 * compte à rebours existante — jamais une direction inventée. Pur, testé.
 */
class CurbUpLoop {

    /** Frames qualifiantes consécutives (un manqué remet à zéro). */
    private var hits: Int = 0

    /** Frames non qualifiantes consécutives (un hit remet à zéro). */
    private var misses: Int = 0

    /** Boucle active (confirmée, pas encore sortie). */
    private var active: Boolean = false

    /**
     * Alimente la boucle avec le [signal] de la frame : vrai si la boucle
     * est active (montée confirmée à moins d'1 m, pas encore sortie).
     */
    fun onSignal(signal: CurbSignal): Boolean {
        val limit = if (active) EXIT_METERS else ENTER_METERS
        if (qualifies(signal, limit)) {
            hits++
            misses = 0
        } else {
            misses++
            hits = 0
        }
        if (!active && hits >= CONFIRM_FRAMES) active = true
        if (active && misses >= EXIT_MISSES) active = false
        return active
    }

    /** Oublie la boucle (nouvelle séance). */
    fun reset() {
        hits = 0
        misses = 0
        active = false
    }

    companion object {
        /** Entrée : montée strictement sous cette distance (ordre user : moins d'1 m). */
        const val ENTER_METERS = 1.0f

        /** Sortie : hystérésis anti-battement autour de l'entrée. */
        const val EXIT_METERS = 1.2f

        /** Frames qualifiantes pour activer (miroir `CurbWatcher`). */
        private const val CONFIRM_FRAMES = 2

        /** Manqués consécutifs pour sortir (trous SAM graciés). */
        private const val EXIT_MISSES = 3
    }
}

/**
 * Frame qualifiante sous [limit] : bord détecté, distance finie sous le
 * seuil, sens montant (`null` = construction manuelle, lue comme une
 * montée — voir [CurbSignal]).
 */
private fun qualifies(signal: CurbSignal, limit: Float): Boolean {
    if (!signal.detected) return false
    val meters = signal.meters ?: return false
    if (!meters.isFinite() || meters >= limit) return false
    return signal.edge == null || signal.edge == CurbEdge.Up
}
