package dev.sam3vision.app.voicenav

/**
 * Textes et décisions de la notification permanente du guidage vocal.
 *
 * Pur Kotlin (aucune classe Android) pour rester testable en JVM :
 * le [GuidanceTrackingService] ne fait que brancher ces valeurs sur
 * `NotificationCompat`. Textes en anglais, formulés pour une annonce
 * vocale (« suivez les annonces », pas « regardez l'écran »).
 */
object GuidanceNotification {

    /** Canal unique du guidage, distinct du suivi parent (`suivi_parent`). */
    const val CHANNEL_ID = "guidage_vocal"

    /** Id de notification, distinct du suivi parent (42). */
    const val NOTIFICATION_ID = 43

    /** Action d'arrêt du guidage (bouton de la notification). */
    const val ACTION_STOP = "dev.sam3vision.app.voicenav.STOP"

    /** Nom du canal affiché dans les réglages système. */
    fun channelName(): String = "Voice guidance"

    /** Titre permanent pendant le guidage. */
    fun title(): String = "Voice guidance active"

    /**
     * Texte permanent : nomme la destination quand elle est connue,
     * sinon un libellé générique (jamais vide, jamais technique).
     */
    fun contentText(destinationLabel: String?): String =
        if (destinationLabel.isNullOrBlank()) {
            "Follow the voice announcements."
        } else {
            "Guiding to ${destinationLabel.trim()} — follow the voice announcements."
        }

    /** Libellé du bouton d'arrêt. */
    fun stopLabel(): String = "Stop guidance"
}
