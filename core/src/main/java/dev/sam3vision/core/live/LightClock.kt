package dev.sam3vision.core.live

/**
 * Cadence d'une lecture **locale** du feu piéton : au plus une lecture toutes
 * les [maxMs], jamais deux pour la même frame.
 *
 * Pourquoi un seau à part, et pas la cadence du direct : l'état du feu est lu
 * dans les pixels, sur l'appareil (`PedLightGate`) — il n'a aucune raison
 * d'attendre l'aller-retour SAM, qui vaut 422 ms médians
 * (`docs/LATENCE-BUDGET.md`). La boîte qui dit **où** est le feu continue de
 * venir du serveur ; la couleur qui dit **quoi** se relit localement sur la
 * frame caméra la plus fraîche, à 150 ms au plus tard.
 *
 * Pur et testé en JVM : aucune horloge système, l'instant est toujours passé en
 * argument.
 */
class LightClock(private val maxMs: Long = MAX_MS) {

    private var lastMs: Long = Long.MIN_VALUE

    /** Vrai si une lecture est due à [nowMs] (et consomme alors le créneau). */
    fun due(nowMs: Long): Boolean {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs < maxMs) return false
        lastMs = nowMs
        return true
    }

    /** Nouvelle séance : la première frame est due tout de suite. */
    fun reset() {
        lastMs = Long.MIN_VALUE
    }

    companion object {
        /** Écart maximal entre deux lectures locales de la couleur du feu. */
        const val MAX_MS = 150L
    }
}
