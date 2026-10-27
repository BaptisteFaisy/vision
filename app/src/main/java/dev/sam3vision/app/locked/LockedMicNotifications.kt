package dev.sam3vision.app.locked

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.sam3vision.core.speech.LockedMicState

/**
 * La notification qui **est** l'interface du micro verrouillé : une seule ligne lisible par
 * TalkBack, un seul bouton, plus « Arrêter ». Elle vit sur l'écran de verrouillage
 * (`VISIBILITY_PUBLIC`) parce que c'est là qu'on la cherche.
 *
 * Le texte vient de [LockedMicState.view] (`:core`, testé en JVM) : ce fichier ne décide rien,
 * il habille.
 */
object LockedMicNotifications {

    /** Une chaîne à part : elle ne se confond pas avec les autres notifications de l'app. */
    const val CHANNEL_ID = "isee_mic_verrou"

    /** Identifiant fixe : une seule notification, toujours remplacée. */
    const val NOTIFICATION_ID = 4_201

    /** Crée la chaîne une fois (idempotent : `createNotificationChannel` écrase à l'identique). */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Micro verrouillé",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Écouter et répondre sans déverrouiller le téléphone."
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Construit la notification de [state] : titre, texte, action principale, arrêt. */
    fun build(context: Context, state: LockedMicState): Notification {
        val view = state.view()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(view.title)
            .setContentText(view.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(view.message))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .addAction(0, "Arrêter", service(context, LockedMicBridge.ACTION_STOP, STOP_REQUEST))

        view.action?.let { label -> builder.addAction(0, label, service(context, LockedMicBridge.ACTION_TOGGLE, TOGGLE_REQUEST)) }
        return builder.build()
    }

    /**
     * Publie la notification. Sans la permission `POST_NOTIFICATIONS` (Android 13+), la
     * notification reste invisible mais le service tourne : on n'échoue pas, on n'affiche rien.
     */
    fun show(context: Context, state: LockedMicState) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, state))
    }

    /** Vrai si l'app a le droit d'afficher une notification sur cette version d'Android. */
    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** L'action du bouton principal est toujours la bascule : fermé → écoute, écoute → envoie. */
    private fun service(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        context,
        code,
        LockedMicBridge.intent(context, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Toucher le corps de la notification ramène à iSee (là où tout s'explique). */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_REQUEST,
        context.packageManager.getLaunchIntentForPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val TOGGLE_REQUEST = 1
    private const val STOP_REQUEST = 2
    private const val OPEN_REQUEST = 3
}