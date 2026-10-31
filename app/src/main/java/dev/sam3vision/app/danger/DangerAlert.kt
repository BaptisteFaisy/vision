package dev.sam3vision.app.danger

/**
 * Une personne qui vient vers le téléphone : piste [trackId], contact estimé
 * dans [ttcSec] secondes, [persons] personnes dans le champ au moment de
 * l'alerte. La voix lira [line] plus tard ; pour l'instant : logcat + statut.
 */
data class DangerAlert(
    val trackId: Int,
    val ttcSec: Float,
    val persons: Int,
) {
    /** Ligne lue à la voix plus tard, affichée en statut dès maintenant. */
    fun line(): String = "⚠ Person straight ahead — contact in ~${ttcSec.coerceAtLeast(1f).toInt()} s"

    companion object {
        /**
         * Seuil d'alerte : on prévient quand le contact est estimé à 6 s ou
         * moins — assez tôt pour réagir, assez tard pour ne pas crier au loup.
         */
        const val ALERT_TTC_SEC = 6f
    }
}
