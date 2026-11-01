package dev.sam3vision.app.glasses

/**
 * Textes et décisions de la notification permanente de la session lunettes-iSee.
 *
 * Pur Kotlin (aucune classe Android) pour rester testable en JVM : le
 * [GlassesSessionService] ne fait que brancher ces valeurs sur
 * `NotificationCompat`. Textes en français, formulés pour quelqu'un dont le
 * téléphone dort dans la poche (la session vit dans les lunettes).
 */
object GlassesSessionNotification {

    /** Canal unique de la session, distinct du suivi parent et du guidage. */
    const val CHANNEL_ID = "lunettes_isee"

    /** Id de notification, distinct du suivi parent (42) et du guidage (43). */
    const val NOTIFICATION_ID = 44

    /** Action d'arrêt de la session (bouton de la notification). */
    const val ACTION_STOP = "dev.sam3vision.app.glasses.STOP"

    /** Nom du canal affiché dans les réglages système. */
    fun channelName(): String = "Session lunettes"

    /** Titre permanent pendant la session. */
    fun title(): String = "Session lunettes iSee active"

    /** Texte permanent : la session survit à la veille, et c'est dit. */
    fun contentText(): String = "La session avec les lunettes continue, même téléphone en veille."

    /** Libellé du bouton d'arrêt (la session tombe pour de bon). */
    fun stopLabel(): String = "Arrêter la session"
}
