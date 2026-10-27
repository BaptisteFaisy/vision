package dev.sam3vision.core.share

/**
 * Politique de cadence du service de suivi permanent (batterie) : pure et
 * testable sans appareil. Écran allumé = cadence normale du partage ; écran
 * éteint = position seule, espacée ; pendant le Direct l'écran publie déjà
 * l'état complet, le service se contente d'attendre.
 */
object ShareTrackingPolicy {

    /** Cadence en poche (écran éteint, app en arrière-plan). */
    const val SCREEN_OFF_DELAY_MS = 15_000L

    /** Attente pendant que le Direct publie (recheck rapide de l'état). */
    const val SCREEN_BUSY_DELAY_MS = 1_000L

    fun nextDelayMs(interactive: Boolean, directPublishing: Boolean): Long = when {
        directPublishing -> SCREEN_BUSY_DELAY_MS
        interactive -> SHARE_INTERVAL_MS
        else -> SCREEN_OFF_DELAY_MS
    }
}
