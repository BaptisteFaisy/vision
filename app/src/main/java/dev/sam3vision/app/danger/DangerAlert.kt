package dev.sam3vision.app.danger

/**
 * Une personne qui vient vers le téléphone : piste [trackId], contact estimé
 * dans [ttcSec] secondes, [persons] personnes dans le champ au moment de
 * l'alerte ([crossing] = traverse le champ, sinon vient de face). La voix
 * lira [line] plus tard ; pour l'instant : logcat + statut.
 */
data class DangerAlert(
    val trackId: Int,
    val ttcSec: Float,
    val persons: Int,
    /** Vrai si la personne traverse (croisée latérale), faux si elle vient de face. */
    val crossing: Boolean = false,
) {
    /** Ligne lue à la voix plus tard, affichée en statut dès maintenant. */
    fun line(): String {
        val seconds = ttcSec.coerceAtLeast(1f).toInt()
        return if (crossing) "⚠ Person crossing — contact in ~$seconds s"
        else "⚠ Person straight ahead — contact in ~$seconds s"
    }

    companion object {
        /**
         * Seuil d'alerte : on prévient quand le contact est estimé à 6 s ou
         * moins — assez tôt pour réagir, assez tard pour ne pas crier au loup.
         */
        const val ALERT_TTC_SEC = 6f
    }
}

/**
 * Nom d'événement de l'alerte pour la voix : [DangerPipeline] l'expose déjà
 * via `onAlert` (canal existant) ; l'abonnement voix (phrase + `SafetyVoice`)
 * reste à câbler côté sonde (fichiers voix, hors périmètre).
 */
typealias DangerEvent = DangerAlert
