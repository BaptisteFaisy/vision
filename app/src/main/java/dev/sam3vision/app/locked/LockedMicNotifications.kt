package dev.sam3vision.app.locked

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.sam3vision.core.speech.LockedMic
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

    /**
     * Une chaîne à part, **et une version dans l'identifiant** : l'importance d'un canal est
     * figée à sa création (Android ignore toute baisse ultérieure). Mesuré sur le téléphone :
     * en `IMPORTANCE_LOW`, HyperOS replie la notification et **cache ses boutons** — l'écran de
     * verrouillage n'offrait donc rien à toucher. `_v2` = `IMPORTANCE_DEFAULT`, sans son.
     */
    const val CHANNEL_ID = "isee_mic_verrou_v2"

    /** Identifiant fixe : une seule notification, toujours remplacée. */
    const val NOTIFICATION_ID = 4_201

    /** Crée la chaîne une fois (idempotent : `createNotificationChannel` écrase à l'identique). */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Micro verrouillé",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Écouter et répondre sans déverrouiller le téléphone."
            setSound(null, null)          // visible et dépliable, mais muette
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // L'ancienne chaîne silencieuse est retirée : sinon elle reste dans les réglages.
        context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
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
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Toucher la notification **est** le geste : HyperOS replie les notifications et
            // cache leurs boutons, or on ne demande pas à un utilisateur non-voyant d'ouvrir un
            // chevron. Un seul appui, n'importe où sur la carte, ouvre (ou envoie) le micro.
            .setContentIntent(service(context, LockedMicBridge.ACTION_TOGGLE, TOGGLE_REQUEST))
            .addAction(0, "Arrêter", service(context, LockedMicBridge.ACTION_STOP, STOP_REQUEST))

        view.action?.let { label -> builder.addAction(0, label, service(context, LockedMicBridge.ACTION_TOGGLE, TOGGLE_REQUEST)) }
        return builder.build()
    }

    /**
     * Publie la notification. Sans la permission `POST_NOTIFICATIONS` (Android 13+), la
     * notification reste invisible mais le service tourne : on n'échoue pas, on n'affiche rien.
     * Ne lève jamais : une publication manquée (permission retirée entre le test et l'appel,
     * système saturé) est notée, le service décide sans elle.
     */
    @SuppressLint("MissingPermission") // garde juste au-dessus : `canNotify` lit POST_NOTIFICATIONS
    fun show(context: Context, state: LockedMicState) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, state))
        }.onFailure {
            Log.w(LockedMic.TAG, "notification : ${it.javaClass.simpleName} — ${it.message}")
        }
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

    /** Canal d'avant la correction (importance basse) : supprimé à la création du nouveau. */
    private const val LEGACY_CHANNEL_ID = "isee_mic_verrou"

    private const val TOGGLE_REQUEST = 1
    private const val STOP_REQUEST = 2
}